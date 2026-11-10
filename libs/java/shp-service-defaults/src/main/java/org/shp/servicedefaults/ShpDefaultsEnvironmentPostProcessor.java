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
 * Load SHP library defaults ({@code classpath*:META-INF/shp/*.properties}, by file name) with the
 * LOWEST PRIORITY, BEFORE auto-configuration is evaluated. (Do not use {@code @PropertySource} on
 * auto-configuration: it loads too late, so conditions such as "endpoint is exposed" may already
 * have been evaluated incorrectly.)
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
