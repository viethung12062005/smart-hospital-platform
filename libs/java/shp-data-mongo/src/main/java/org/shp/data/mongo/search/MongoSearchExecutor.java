package org.shp.data.mongo.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.shp.fhir.search.Criterion;
import org.shp.fhir.search.Criterion.DateCriterion;
import org.shp.fhir.search.Criterion.ReferenceCriterion;
import org.shp.fhir.search.Criterion.StringCriterion;
import org.shp.fhir.search.Criterion.Token;
import org.shp.fhir.search.Criterion.TokenCriterion;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Executes a parsed FHIR search (SHR-03) against MongoDB: compiles token, reference, date and name
 * criteria into one query, hints the driving index from {@link SearchIndexes} and paginates by
 * keyset.
 *
 * <p>{@link #search(SearchQuery)} returns the first page. The API layer decodes the opaque, signed
 * {@code _page_token} (SHR-04) to a {@link SearchCursor} and calls {@link #search(SearchQuery,
 * SearchCursor)}; the executor never interprets the token. Pagination is a keyset on {@code _id}
 * (UUIDv7, unique and ordered), so a page does not use {@code skip} and does not shift when rows
 * are written between requests.
 *
 * <p>Criteria are AND-ed; the values inside one token or reference criterion are OR-ed. Name search
 * follows 03-db-clinical-document §2.3: the name is normalized (NFD, no diacritics, lowercase) and
 * every token except the last matches exactly while the last matches by anchored prefix.
 *
 * <p>The service must call {@link SearchIndexes#ensureIndexes(MongoTemplate)} before searching; a
 * hinted index that does not exist makes MongoDB reject the query rather than silently scan the
 * collection.
 */
public final class MongoSearchExecutor {
  private static final String ID = "_id";

  private final MongoTemplate mongo;
  private final SearchCatalog catalog;

  public MongoSearchExecutor(MongoTemplate mongo) {
    this(mongo, SearchCatalog.standard());
  }

  public MongoSearchExecutor(MongoTemplate mongo, SearchCatalog catalog) {
    this.mongo = mongo;
    this.catalog = catalog;
  }

  /**
   * The first page of a search.
   *
   * @throws IllegalArgumentException when the query carries an opaque page token; decode it first
   */
  public SearchPage search(SearchQuery query) {
    if (query.pageToken() != null) {
      throw new IllegalArgumentException(
          "The executor does not decode _page_token; decode it to a SearchCursor first (SHR-04)");
    }
    return search(query, null);
  }

  /**
   * One page of a search, after {@code cursor} ({@code null} for the first page). The opaque token
   * inside {@code query} is ignored: the decoded cursor is authoritative.
   */
  public SearchPage search(SearchQuery query, SearchCursor cursor) {
    SearchPlan plan = plan(query, cursor);
    List<Document> found = mongo.find(plan.query(), Document.class, plan.collection());
    boolean more = found.size() > query.count();
    List<Document> items = List.copyOf(found.subList(0, more ? query.count() : found.size()));
    SearchCursor next = null;
    if (more) {
      Object id = items.getLast().get(ID);
      if (!(id instanceof String value)) {
        throw new IllegalStateException("Search requires a string _id (03 §2.1)");
      }
      next = new SearchCursor(value);
    }
    return new SearchPage(items, next);
  }

  /**
   * Compile the query without running it; used by callers that need to inspect or explain the plan.
   */
  public SearchPlan plan(SearchQuery query, SearchCursor cursor) {
    if (query.count() < 1 || query.count() > SearchParser.MAX_COUNT) {
      throw new IllegalArgumentException(
          "_count must be between 1 and " + SearchParser.MAX_COUNT + ": " + query.count());
    }
    if (query.criteria().isEmpty()) {
      throw new IllegalArgumentException("A MongoDB search needs at least one criterion");
    }
    SearchCatalog.Collection collection = catalog.require(query.resourceType());

    List<Criteria> parts = new ArrayList<>();
    Set<String> present = new LinkedHashSet<>();
    for (Criterion criterion : query.criteria()) {
      SearchCatalog.Field field = collection.field(criterion.param());
      if (field == null) {
        throw new IllegalArgumentException(
            "Unsupported search parameter " + criterion.param() + " for " + query.resourceType());
      }
      present.add(criterion.param());
      parts.add(clause(criterion, field));
    }
    if (cursor != null) {
      parts.add(Criteria.where(ID).gt(cursor.afterId()));
    }

    Criteria criteria = parts.size() == 1 ? parts.getFirst() : new Criteria().andOperator(parts);
    Query compiled =
        Query.query(criteria).with(Sort.by(Sort.Direction.ASC, ID)).limit(query.count() + 1);
    String index = collection.indexFor(present);
    if (index != null) {
      compiled.withHint(index);
    }
    return new SearchPlan(collection.collection(), compiled, index);
  }

  private static Criteria clause(Criterion criterion, SearchCatalog.Field field) {
    return switch (field.kind()) {
      case NAME -> name(field.path(), expect(StringCriterion.class, criterion));
      case TOKEN -> token(field.path(), expect(TokenCriterion.class, criterion));
      case REFERENCE -> reference(field.path(), expect(ReferenceCriterion.class, criterion));
      case DATE -> date(field.path(), expect(DateCriterion.class, criterion));
    };
  }

  private static <T extends Criterion> T expect(Class<T> type, Criterion criterion) {
    if (!type.isInstance(criterion)) {
      throw new IllegalArgumentException(
          "Criterion "
              + criterion.param()
              + " is a "
              + criterion.getClass().getSimpleName()
              + ", expected "
              + type.getSimpleName());
    }
    return type.cast(criterion);
  }

  private static Criteria token(String path, TokenCriterion criterion) {
    List<Token> usable = criterion.anyOf().stream().filter(t -> t.code() != null).toList();
    if ("search.mrn".equals(path)) {
      // The normalized MRN field carries the system implicitly (09 C6: urn:shp:mrn).
      usable =
          usable.stream()
              .filter(t -> t.system() == null || SearchCatalog.MRN_SYSTEM.equals(t.system()))
              .toList();
    }
    if (usable.isEmpty()) {
      return noMatch();
    }
    return Criteria.where(path).in(usable.stream().map(Token::code).distinct().toList());
  }

  private static Criteria reference(String path, ReferenceCriterion criterion) {
    if (criterion.anyOfIds().isEmpty()) {
      return noMatch();
    }
    return Criteria.where(path).in(criterion.anyOfIds());
  }

  private static Criteria date(String path, DateCriterion criterion) {
    if (criterion.from() == null && criterion.to() == null) {
      return noMatch();
    }
    Criteria criteria = Criteria.where(path);
    if (criterion.from() != null) {
      criteria = criteria.gte(Date.from(criterion.from()));
    }
    if (criterion.to() != null) {
      criteria = criteria.lt(Date.from(criterion.to()));
    }
    return criteria;
  }

  private static Criteria name(String path, StringCriterion criterion) {
    List<String> tokens = nameTokens(criterion.value());
    if (tokens.isEmpty()) {
      return noMatch();
    }
    List<Criteria> perToken = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      perToken.add(
          i == tokens.size() - 1
              ? Criteria.where(path).regex("^" + Pattern.quote(tokens.get(i)))
              : Criteria.where(path).is(tokens.get(i)));
    }
    return perToken.size() == 1 ? perToken.getFirst() : new Criteria().andOperator(perToken);
  }

  /**
   * Normalize a name the way the store writes {@code search.nameTokens}: NFD, diacritics removed,
   * the Vietnamese d with stroke folded to {@code d}, lowercase, split on whitespace.
   */
  static List<String> nameTokens(String value) {
    String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .toLowerCase(Locale.ROOT)
            .strip();
    if (normalized.isEmpty()) {
      return List.of();
    }
    return Arrays.stream(normalized.split("\\s+")).filter(t -> !t.isEmpty()).toList();
  }

  /** A clause that can never match, for a criterion that carries no usable value. */
  private static Criteria noMatch() {
    return Criteria.where(ID).exists(false);
  }
}
