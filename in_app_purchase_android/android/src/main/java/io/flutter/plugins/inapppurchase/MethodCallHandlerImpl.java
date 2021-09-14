// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.inapppurchase;

import static io.flutter.plugins.inapppurchase.Translator.fromPurchaseHistoryRecordList;
import static io.flutter.plugins.inapppurchase.Translator.fromPurchasesResult;
import static io.flutter.plugins.inapppurchase.Translator.fromSkuDetailsList;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingFlowParams.ProrationMode;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PriceChangeFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Handles method channel for the plugin. */
class MethodCallHandlerImpl
    implements MethodChannel.MethodCallHandler, Application.ActivityLifecycleCallbacks {

  private static final String TAG = "InAppPurchasePlugin";
  private static final String LOAD_SKU_DOC_URL =
      "https://github.com/flutter/plugins/blob/master/packages/in_app_purchase/in_app_purchase/README.md#loading-products-for-sale";

  @Nullable private BillingClient billingClient;
  private final BillingClientFactory billingClientFactory;

  @Nullable private Activity activity;
  private final Context applicationContext;
  private final MethodChannel methodChannel;

  private HashMap<String, SkuDetails> cachedSkus = new HashMap<>();

  /** Constructs the MethodCallHandlerImpl */
  MethodCallHandlerImpl(
      @Nullable Activity activity,
      @NonNull Context applicationContext,
      @NonNull MethodChannel methodChannel,
      @NonNull BillingClientFactory billingClientFactory) {
    this.billingClientFactory = billingClientFactory;
    this.applicationContext = applicationContext;
    this.activity = activity;
    this.methodChannel = methodChannel;
  }

  /**
   * Sets the activity. Should be called as soon as the the activity is available. When the activity
   * becomes unavailable, call this method again with {@code null}.
   */
  void setActivity(@Nullable Activity activity) {
    this.activity = activity;
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityResumed(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

  @Override
  public void onActivityDestroyed(Activity activity) {
    if (this.activity == activity && this.applicationContext != null) {
      ((Application) this.applicationContext).unregisterActivityLifecycleCallbacks(this);
      endBillingClientConnection();
    }
  }

  @Override
  public void onActivityStopped(Activity activity) {}

  void onDetachedFromActivity() {
    endBillingClientConnection();
  }

  @Override
  public void onMethodCall(MethodCall call, MethodChannel.Result result) {
    switch (call.method) {
      case InAppPurchasePlugin.MethodNames.IS_READY:
        Log.d(TAG, InAppPurchasePlugin.MethodNames.IS_READY);
        isReady(result);
        break;
      case InAppPurchasePlugin.MethodNames.START_CONNECTION:
        Log.d(TAG, "start connection.");
        startConnection(
            (int) call.argument("handle"),
            (boolean) call.argument("enablePendingPurchases"),
            result);
        Log.d(TAG, "start connection end.");
        break;
      case InAppPurchasePlugin.MethodNames.END_CONNECTION:
        Log.d(TAG, "end connection.");
        endConnection(result);
        break;
      case InAppPurchasePlugin.MethodNames.QUERY_SKU_DETAILS:
        Log.d(TAG, InAppPurchasePlugin.MethodNames.QUERY_SKU_DETAILS);
        List<String> skusList = call.argument("skusList");
        querySkuDetailsAsync((String) call.argument("skuType"), skusList, result);
        break;
      case InAppPurchasePlugin.MethodNames.LAUNCH_BILLING_FLOW:
        Log.d(TAG, InAppPurchasePlugin.MethodNames.LAUNCH_BILLING_FLOW);
        launchBillingFlow(
            (String) call.argument("sku"),
            (String) call.argument("accountId"),
            (String) call.argument("obfuscatedProfileId"),
            (String) call.argument("oldSku"),
            (String) call.argument("purchaseToken"),
            call.hasArgument("prorationMode")
                ? (int) call.argument("prorationMode")
                : ProrationMode.UNKNOWN_SUBSCRIPTION_UPGRADE_DOWNGRADE_POLICY,
            result);
        break;
      case InAppPurchasePlugin.MethodNames.QUERY_PURCHASES:
        // restorePurchases()もここ
        Log.d(TAG, InAppPurchasePlugin.MethodNames.QUERY_PURCHASES);
        queryPurchases((String) call.argument("skuType"), result);
        break;
      case InAppPurchasePlugin.MethodNames.QUERY_PURCHASE_HISTORY_ASYNC:
        queryPurchaseHistoryAsync((String) call.argument("skuType"), result);
        break;
      case InAppPurchasePlugin.MethodNames.CONSUME_PURCHASE_ASYNC:
        consumeAsync((String) call.argument("purchaseToken"), result);
        break;
      case InAppPurchasePlugin.MethodNames.ACKNOWLEDGE_PURCHASE:
        acknowledgePurchase((String) call.argument("purchaseToken"), result);
        break;
      case InAppPurchasePlugin.MethodNames.IS_FEATURE_SUPPORTED:
        isFeatureSupported((String) call.argument("feature"), result);
        break;
      case InAppPurchasePlugin.MethodNames.LAUNCH_PRICE_CHANGE_CONFIRMATION_FLOW:
        launchPriceChangeConfirmationFlow((String) call.argument("sku"), result);
        break;
      default:
        result.notImplemented();
    }
  }

  private void endConnection(final MethodChannel.Result result) {
    endBillingClientConnection();
    result.success(null);
  }

  private void endBillingClientConnection() {
    if (billingClient != null) {
      billingClient.endConnection();
      billingClient = null;
    }
  }

  private void isReady(MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }

    result.success(billingClient.isReady());
  }

  private void querySkuDetailsAsync(
      final String skuType, final List<String> skusList, final MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }

    SkuDetailsParams params =
        SkuDetailsParams.newBuilder().setType(skuType).setSkusList(skusList).build();
    billingClient.querySkuDetailsAsync(
        params,
            (billingResult, skuDetailsList) -> activity.runOnUiThread(() -> {
              Log.d(TAG, "welcome. querySkuDetailsAsync skuDetailsResponseListener");
              updateCachedSkus(skuDetailsList);
              final Map<String, Object> skuDetailsResponse = new HashMap<>();
              skuDetailsResponse.put("billingResult", Translator.fromBillingResult(billingResult));
              skuDetailsResponse.put("skuDetailsList", fromSkuDetailsList(skuDetailsList));
              Log.d(TAG, "querySkuDetailsAsync: " + skuDetailsResponse);

              result.success(skuDetailsResponse);
            }));
  }

  private void launchBillingFlow(
      String sku,
      @Nullable String accountId,
      @Nullable String obfuscatedProfileId,
      @Nullable String oldSku,
      @Nullable String purchaseToken,
      int prorationMode,
      MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }

    SkuDetails skuDetails = cachedSkus.get(sku);
    if (skuDetails == null) {
      result.error(
          "NOT_FOUND",
          String.format(
              "Details for sku %s are not available. It might because skus were not fetched prior to the call. Please fetch the skus first. An example of how to fetch the skus could be found here: %s",
              sku, LOAD_SKU_DOC_URL),
          null);
      return;
    }

    if (oldSku == null
        && prorationMode != ProrationMode.UNKNOWN_SUBSCRIPTION_UPGRADE_DOWNGRADE_POLICY) {
      result.error(
          "IN_APP_PURCHASE_REQUIRE_OLD_SKU",
          "launchBillingFlow failed because oldSku is null. You must provide a valid oldSku in order to use a proration mode.",
          null);
      return;
    } else if (oldSku != null && !cachedSkus.containsKey(oldSku)) {
      result.error(
          "IN_APP_PURCHASE_INVALID_OLD_SKU",
          String.format(
              "Details for sku %s are not available. It might because skus were not fetched prior to the call. Please fetch the skus first. An example of how to fetch the skus could be found here: %s",
              oldSku, LOAD_SKU_DOC_URL),
          null);
      return;
    }

    if (activity == null) {
      result.error(
          "ACTIVITY_UNAVAILABLE",
          "Details for sku "
              + sku
              + " are not available. This method must be run with the app in foreground.",
          null);
      return;
    }

    // 追加しようとしているところ
    // https://github.com/android/play-billing-samples/blob/f9ae2d55c3699474e26ca0185a5ff38afb9df153/ClassyTaxiAppKotlin/app/src/main/java/com/example/subscriptions/ui/BillingViewModel.kt#L241
    //
    SubscriptionUpdateParams.Builder subscriptionUpdateParams
            = SubscriptionUpdateParams.newBuilder();

    BillingFlowParams.Builder billingBuilder
            = BillingFlowParams.newBuilder().setSkuDetails(skuDetails);
    if (purchaseToken != null) {
      subscriptionUpdateParams.setOldSkuPurchaseToken(purchaseToken);
      subscriptionUpdateParams.setReplaceSkusProrationMode(prorationMode);

      billingBuilder
              .setSubscriptionUpdateParams(subscriptionUpdateParams.build())
              .setObfuscatedAccountId(accountId)
              .setObfuscatedProfileId(obfuscatedProfileId);
    }

    BillingFlowParams billingFlowParams = billingBuilder.build();

    result.success(
        Translator.fromBillingResult(
            billingClient.launchBillingFlow(activity, billingFlowParams)));
  }

  private void consumeAsync(String purchaseToken, final MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }

    ConsumeResponseListener listener =
            (billingResult, outToken) -> result.success(Translator.fromBillingResult(billingResult));
    ConsumeParams.Builder paramsBuilder =
        ConsumeParams.newBuilder().setPurchaseToken(purchaseToken);

    ConsumeParams params = paramsBuilder.build();

    billingClient.consumeAsync(params, listener);
  }

  private void queryPurchases(String skuType, MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }

    // Like in our connect call, consider the billing client responding a "success" here regardless
    // of status code.

    billingClient.queryPurchasesAsync(skuType,(billingResult, list) -> {
      if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
        Log.e(TAG, "Problem getting purchases: " +
                billingResult.getDebugMessage());
      } else {
        activity.runOnUiThread(()->{
          result.success(fromPurchasesResult(new Purchase.PurchasesResult(billingResult, list)));
        });
      }
    });
  }

  private void queryPurchaseHistoryAsync(String skuType, final MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }

    billingClient.queryPurchaseHistoryAsync(
        skuType,
            (billingResult, purchasesList) -> {
              final Map<String, Object> serialized = new HashMap<>();
              serialized.put("billingResult", Translator.fromBillingResult(billingResult));
              serialized.put(
                  "purchaseHistoryRecordList", fromPurchaseHistoryRecordList(purchasesList));
              result.success(serialized);
            });
  }

  private void startConnection(
      final int handle, final boolean enablePendingPurchases, final MethodChannel.Result result) {
    if (billingClient == null) {
      billingClient =
          billingClientFactory.createBillingClient(
              applicationContext, methodChannel, enablePendingPurchases);
    }

    billingClient.startConnection(
        new BillingClientStateListener() {
          private boolean alreadyFinished = false;

          @Override
          public void onBillingSetupFinished(BillingResult billingResult) {
            activity.runOnUiThread(()->{
              Log.d(TAG, "onBillingSetupFinished");
              if (alreadyFinished) {
                Log.d(TAG, "Tried to call onBillingSetupFinished multiple times.");
                return;
              }
              alreadyFinished = true;
              // Consider the fact that we've finished a success, leave it to the Dart side to
              // validate the responseCode.
              Log.d(TAG, "onBillingSetupFinished: " + Translator.fromBillingResult(billingResult));
              result.success(Translator.fromBillingResult(billingResult));
            });
          }

          @Override
          public void onBillingServiceDisconnected() {
            final Map<String, Object> arguments = new HashMap<>();
            arguments.put("handle", handle);
            methodChannel.invokeMethod(InAppPurchasePlugin.MethodNames.ON_DISCONNECT, arguments);
          }
        });
  }

  private void acknowledgePurchase(String purchaseToken, final MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }
    AcknowledgePurchaseParams params =
        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build();
    billingClient.acknowledgePurchase(
        params,
        new AcknowledgePurchaseResponseListener() {
          @Override
          public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
            result.success(Translator.fromBillingResult(billingResult));
          }
        });
  }

  private void updateCachedSkus(@Nullable List<SkuDetails> skuDetailsList) {
    if (skuDetailsList == null) {
      return;
    }

    for (SkuDetails skuDetails : skuDetailsList) {
      cachedSkus.put(skuDetails.getSku(), skuDetails);
    }
  }

  private void launchPriceChangeConfirmationFlow(String sku, MethodChannel.Result result) {
    if (activity == null) {
      result.error(
          "ACTIVITY_UNAVAILABLE",
          "launchPriceChangeConfirmationFlow is not available. "
              + "This method must be run with the app in foreground.",
          null);
      return;
    }
    if (billingClientError(result)) {
      return;
    }
    // Note that assert doesn't work on Android (see https://stackoverflow.com/a/6176529/5167831 and https://stackoverflow.com/a/8164195/5167831)
    // and that this assert is only added to silence the analyser. The actual null check
    // is handled by the `billingClientError()` call.
    assert billingClient != null;

    SkuDetails skuDetails = cachedSkus.get(sku);
    if (skuDetails == null) {
      result.error(
          "NOT_FOUND",
          String.format(
              "Details for sku %s are not available. It might because skus were not fetched prior to the call. Please fetch the skus first. An example of how to fetch the skus could be found here: %s",
              sku, LOAD_SKU_DOC_URL),
          null);
      return;
    }

    PriceChangeFlowParams params =
        new PriceChangeFlowParams.Builder().setSkuDetails(skuDetails).build();
    billingClient.launchPriceChangeConfirmationFlow(
        activity,
        params,
        billingResult -> {
          result.success(Translator.fromBillingResult(billingResult));
        });
  }

  private boolean billingClientError(MethodChannel.Result result) {
    if (billingClient != null) {
      return false;
    }

    result.error("UNAVAILABLE", "BillingClient is unset. Try reconnecting.", null);
    return true;
  }

  private void isFeatureSupported(String feature, MethodChannel.Result result) {
    if (billingClientError(result)) {
      return;
    }
    assert billingClient != null;
    BillingResult billingResult = billingClient.isFeatureSupported(feature);
    result.success(billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK);
  }
}
