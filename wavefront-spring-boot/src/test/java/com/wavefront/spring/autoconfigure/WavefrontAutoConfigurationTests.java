package com.wavefront.spring.autoconfigure;

import java.util.function.Function;
import java.util.function.Supplier;

import brave.Tracer;
import brave.TracingCustomizer;
import brave.handler.FinishedSpanHandler;
import com.wavefront.opentracing.WavefrontTracer;
import com.wavefront.opentracing.reporting.Reporter;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.wavefront.WavefrontMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.sleuth.autoconfig.TraceAutoConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link WavefrontAutoConfiguration}.
 *
 * @author Stephane Nicoll
 */
class WavefrontAutoConfigurationTests {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(WavefrontAutoConfiguration.class));

  @Test
  void applicationTagsIsConfiguredFromPropertiesWhenNoneExists() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .run((context) -> {
          assertThat(context).hasSingleBean(ApplicationTags.class);
          ApplicationTags tags = context.getBean(ApplicationTags.class);
          assertThat(tags.getApplication()).isEqualTo("test-app");
          assertThat(tags.getService()).isEqualTo("test-service");
          assertThat(tags.getCluster()).isNull();
          assertThat(tags.getShard()).isNull();
          assertThat(tags.getCustomTags()).isEmpty();
        });
  }

  @Test
  void applicationTagsCanBeCustomized() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .withBean(ApplicationTagsBuilderCustomizer.class,
            () -> (builder) -> builder.cluster("test-cluster").shard("test-shard"))
        .run((context) -> {
          assertThat(context).hasSingleBean(ApplicationTags.class);
          ApplicationTags tags = context.getBean(ApplicationTags.class);
          assertThat(tags.getApplication()).isEqualTo("test-app");
          assertThat(tags.getService()).isEqualTo("test-service");
          assertThat(tags.getCluster()).isEqualTo("test-cluster");
          assertThat(tags.getShard()).isEqualTo("test-shard");
          assertThat(tags.getCustomTags()).isEmpty();
        });
  }

  @Test
  void applicationTagsIsReusedWhenCustomInstanceExists() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .withBean(ApplicationTags.class,
            () -> new ApplicationTags.Builder("another-app", "another-service").build())
        .run((context) -> {
          assertThat(context).hasSingleBean(ApplicationTags.class);
          ApplicationTags tags = context.getBean(ApplicationTags.class);
          assertThat(tags.getApplication()).isEqualTo("another-app");
          assertThat(tags.getService()).isEqualTo("another-service");
          assertThat(tags.getCluster()).isNull();
          assertThat(tags.getShard()).isNull();
          assertThat(tags.getCustomTags()).isEmpty();
        });
  }

  @Test
  void applicationTagsAreExportedToWavefrontRegistry() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app",
            "wavefront.application.service=test-service")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class))).run((context) -> {
      MeterRegistry registry = context.getBean(MeterRegistry.class);
      registry.counter("my.counter", "env", "qa");
      assertThat(registry.find("my.counter").tags("env", "qa").tags("application", "test-app")
          .tags("service", "test-service").counter()).isNotNull();
    });
  }

  @Test
  void applicationTagsWithFullInformationAreExportedToWavefrontRegistry() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app",
            "wavefront.application.service=test-service", "wavefront.application.cluster=test-cluster",
            "wavefront.application.shard=test-shard")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class))).run((context) -> {
      MeterRegistry registry = context.getBean(MeterRegistry.class);
      registry.counter("my.counter", "env", "qa");
      assertThat(registry.find("my.counter").tags("env", "qa").tags("application", "test-app")
          .tags("service", "test-service").tags("cluster", "test-cluster").tags("shard", "test-shard")
          .counter()).isNotNull();
    });
  }

  @Test
  void applicationTagsAreNotExportedToNonWavefrontRegistry() {
    this.contextRunner
        .withPropertyValues("wavefront.application.name=test-app", "wavefront.application.service=test-service")
        .with(metrics()).withConfiguration(AutoConfigurations.of(SimpleMetricsExportAutoConfiguration.class))
        .run((context) -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          registry.counter("my.counter", "env", "qa");
          assertThat(registry.find("my.counter").tags("env", "qa")).isNotNull();
          assertThat(registry.find("my.counter").tags("env", "qa").tags("application", "test-app")
              .tags("service", "test-service").tags("cluster", "test-cluster").tags("shard", "test-shard")
              .counter()).isNull();
        });
  }

  @Test
  void tracingWithSleuthIsConfiguredWithWavefrontSender() {
    WavefrontSender sender = mock(WavefrontSender.class);
    this.contextRunner.withPropertyValues()
        .with(wavefrontMetrics(() -> sender))
        .with(sleuth())
        .run((context) -> {
          assertThat(context).hasSingleBean(TracingCustomizer.class);
          assertThat(context.getBean(Tracer.class))
              .extracting("finishedSpanHandler.handlers")
              .asInstanceOf(InstanceOfAssertFactories.array(FinishedSpanHandler[].class))
              .filteredOn(h -> h instanceof WavefrontSleuthSpanHandler)
              .hasSize(1)
              .extracting("wavefrontSender")
              .contains(sender);
        });
  }

  @Test
  void tracingWithOpenTracingBacksOffWhenSpringCloudSleuthIsAvailable() {
    this.contextRunner.with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run((context) -> assertThat(context).hasSingleBean(TracingCustomizer.class).doesNotHaveBean(io.opentracing.Tracer.class));
  }

  @Test
  void tracingWithOpenTracingCanBeConfiguredWithWavefrontSenderWhenSleuthIsNotAvailable() {
    this.contextRunner
        .withClassLoader(new FilteredClassLoader("org.springframework.cloud.sleuth"))
        .with(wavefrontMetrics(() -> {
          WavefrontSender sender = mock(WavefrontSender.class);
          given(sender.getFailureCount()).willReturn(42);
          return sender;
        })).run((context) -> {
      assertThat(context).hasSingleBean(io.opentracing.Tracer.class).hasSingleBean(WavefrontTracer.class);
      Reporter reporter = (Reporter) ReflectionTestUtils.getField(context.getBean(WavefrontTracer.class),
          "reporter");
      assertThat(reporter.getFailureCount()).isEqualTo(42);
    });
  }

  @Test
  void tracingCanBeDisabled() {
    this.contextRunner.withPropertyValues("wavefront.tracing.enabled=false")
        .with(wavefrontMetrics(() -> mock(WavefrontSender.class)))
        .run((context) -> assertThat(context).doesNotHaveBean(TracingCustomizer.class)
            .doesNotHaveBean(io.opentracing.Tracer.class));
  }

  @Test
  void tracingIsNotConfiguredWithNonWavefrontRegistry() {
    this.contextRunner.with(metrics()).run((context) -> assertThat(context).doesNotHaveBean(Tracer.class));
  }

  @SuppressWarnings("unchecked")
  private static <T extends AbstractApplicationContextRunner<?, ?, ?>> Function<T, T> wavefrontMetrics(
      Supplier<WavefrontSender> wavefrontSender) {
    return (runner) -> (T) runner.withBean(WavefrontSender.class, wavefrontSender)
        .withConfiguration(AutoConfigurations.of(WavefrontMetricsExportAutoConfiguration.class))
        .with(metrics());
  }

  @SuppressWarnings("unchecked")
  private static <T extends AbstractApplicationContextRunner<?, ?, ?>> Function<T, T> metrics() {
    return (runner) -> (T) runner.withPropertyValues("management.metrics.use-global-registry=false")
        .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class));
  }

  @SuppressWarnings("unchecked")
  private static <T extends AbstractApplicationContextRunner<?, ?, ?>> Function<T, T> sleuth() {
    return (runner) -> (T) runner.withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class));
  }

}
