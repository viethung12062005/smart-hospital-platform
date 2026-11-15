The service's Flyway migrations: `V1__<short_description>.sql`, `V2__...`. Source DDL: `docs/03-architecture/detailed-design/02-db-scheduling.md`.

**Never edit a merged migration file, not even a comment or whitespace.** Flyway stores a checksum of the whole file; a database
that already applied the migration refuses to start when the file changes ("Migration checksum mismatch"). To change the
schema, add a new `V<next>__...sql`. For this reason the comments in the files merged before the English-only rule
(`V001__schedule_template_slot.sql` here and `libs/java/shp-data-postgres/.../V0_1__shp_messaging.sql`) stay in Vietnamese.
