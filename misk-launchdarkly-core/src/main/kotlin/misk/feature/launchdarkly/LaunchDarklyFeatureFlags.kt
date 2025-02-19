package misk.feature.launchdarkly

import com.google.common.util.concurrent.AbstractIdleService
import com.launchdarkly.client.EvaluationDetail
import com.launchdarkly.client.EvaluationReason
import com.launchdarkly.client.LDClientInterface
import com.launchdarkly.client.LDUser
import com.launchdarkly.shaded.com.google.common.base.Preconditions.checkState
import misk.feature.Attributes
import misk.feature.Feature
import misk.feature.FeatureFlags
import misk.feature.FeatureService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [FeatureFlags] using LaunchDarkly's Java SDK.
 * See https://docs.launchdarkly.com/docs/java-sdk-reference documentation.
 */
@Singleton
class LaunchDarklyFeatureFlags @Inject constructor(
  private val ldClient: LDClientInterface
) : AbstractIdleService(), FeatureFlags, FeatureService {
  override fun startUp() {
    var attempts = 50
    val intervalMillis = 100L

    // LaunchDarkly has its own threads for initialization. We just need to keep checking until
    // it's done. Unfortunately there's no latch or event to wait on.
    while (!ldClient.initialized() && attempts > 0) {
      Thread.sleep(intervalMillis)
      attempts--
    }

    if (attempts == 0 && !ldClient.initialized()) {
      throw Exception("LaunchDarkly did not initialize in 5 seconds")
    }
  }

  override fun shutDown() {
    ldClient.flush()
    ldClient.close()
  }

  override fun getBoolean(feature: Feature, key: String, attributes: Attributes): Boolean {
    val result = ldClient.boolVariationDetail(feature.name, buildUser(key, attributes), false)
    checkDefaultNotUsed(feature, result)
    return result.value
  }

  override fun getInt(feature: Feature, key: String, attributes: Attributes): Int {
    checkInitialized()
    val result = ldClient.intVariationDetail(feature.name, buildUser(key, attributes), 0)
    checkDefaultNotUsed(feature, result)
    return result.value
  }

  override fun getString(feature: Feature, key: String, attributes: Attributes): String {
    checkInitialized()
    val result = ldClient.stringVariationDetail(feature.name, buildUser(key, attributes), "")
    checkDefaultNotUsed(feature, result)
    return result.value
  }

  override fun <T : Enum<T>> getEnum(
    feature: Feature,
    key: String,
    clazz: Class<T>,
    attributes: Attributes
  ): T {
    checkInitialized()
    val result = ldClient.stringVariationDetail(feature.name, buildUser(key, attributes), "")
    checkDefaultNotUsed(feature, result)
    return java.lang.Enum.valueOf(clazz, result.value.toUpperCase())
  }

  private fun checkInitialized() {
    checkState(ldClient.initialized(),
        "LaunchDarkly feature flags not initialized. Did you forget to make your service depend on [FeatureFlags]?")
  }

  private fun <T> checkDefaultNotUsed(feature: Feature, detail: EvaluationDetail<T>) {
    if (!detail.isDefaultValue) {
      return
    }

    if (detail.reason.kind == EvaluationReason.Kind.ERROR) {
      throw RuntimeException("Feature flag $feature evaluation failed: ${detail.reason}")
    }

    throw IllegalStateException("Feature flag $feature is off but no off variation is specified")
  }

  private fun buildUser(token: String, attributes: Attributes): LDUser {
    val builder = LDUser.Builder(token)
    attributes.text.forEach { (k, v) ->
      when (k) {
        // LaunchDarkly has some built-in keys that have to be initialized with their named
        // methods.
        "secondary" -> builder.secondary(k)
        "ip" -> builder.ip(k)
        "email" -> builder.email(k)
        "name" -> builder.name(k)
        "avatar" -> builder.avatar(k)
        "firstName" -> builder.firstName(k)
        "lastName" -> builder.lastName(k)
        "country" -> builder.country(k)
        else -> builder.privateCustom(k, v)
      }
    }
    if (attributes.number != null) {
      attributes.number!!.forEach { (k, v) -> builder.privateCustom(k, v) }
    }
    if (attributes.anonymous) {
      // This prevents the user from being stored in the LaunchDarkly dashboard, see
      // https://docs.launchdarkly.com/docs/anonymous-users
      builder.anonymous(true)
    }
    return builder.build()
  }
}