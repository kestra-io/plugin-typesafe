package io.kestra.plugin.typesafe;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.tasks.retrys.Exponential;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the fixed exponential retry configuration without waiting through real backoffs.
 * Behavioral coverage (429/529 exhaustion, Retry-After) lives in {@link EvaluateTest}.
 */
class RetryPolicyTest {
    @Test
    void exponentialBackoffConfiguration() {
        Exponential policy = AbstractTypeSafe.retryPolicy();

        assertThat(policy.getInterval(), is(Duration.ofSeconds(1)));
        assertThat(policy.getDelayFactor(), is(2.0));
        assertThat(policy.getMaxInterval(), is(Duration.ofSeconds(8)));
        assertThat(policy.getMaxAttempts(), is(4));
    }
}
