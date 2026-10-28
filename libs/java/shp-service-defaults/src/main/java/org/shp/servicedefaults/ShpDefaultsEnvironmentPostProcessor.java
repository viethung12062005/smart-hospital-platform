package org.shp.servicedefaults;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * Nạp mặc định của các thư viện SHP ({@code classpath*:META-INF/shp/*.properties}, theo tên file)
 * với ĐỘ ƯU TIÊN THẤP NHẤT, TRƯỚC khi auto-configuration được đánh giá. (Không dùng
 * {@code @PropertySource} trên auto-configuration: nó được nạp quá muộn nên điều kiện như "endpoint
 * có được expose" có thể đã bị đánh giá sai.)
 */
public class ShpDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    try {
      Resource[] files =
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:META-INF/shp/*.properties");
      Arrays.sort(files, Comparator.comparing(Resource::getFilename));
      for (Resource r : files) {
        String name = "shp-defaults:" + r.getFilename();
        if (!env.getPropertySources().contains(name)) {
          env.getPropertySources().addLast(new ResourcePropertySource(name, r));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
