// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.inapppurchase;

import static io.flutter.plugins.inapppurchase.Translator.fromSkuDetailsList;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.MethodChannel;

/** The implementation for {@link BillingClientFactory} for the plugin. */
final class BillingClientFactoryImpl implements BillingClientFactory, SkuDetailsResponseListener {

  private static final String TAG = "BillingClientFactory";
  private static final long SKU_DETAILS_REQUERY_TIME = 1000L * 60L * 60L * 4L; // 4 hours
  private long skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME;

  private MutableLiveData<Map<String, Object>> skuDetailsListLiveData = new MutableLiveData();
  private HashMap<String, SkuDetails> cachedSkus = new HashMap<>();

  // Billing client, connection, cached data
  private BillingClient billingClient;

  @Override
  public BillingClient createBillingClient(
      Context context,
      MethodChannel channel,
      boolean enablePendingPurchases
  ) {
    BillingClient.Builder builder = BillingClient.newBuilder(context);
    if (enablePendingPurchases) {
      builder.enablePendingPurchases();
    }
    billingClient = builder.setListener(new PluginPurchaseListener(channel)).build();
    return billingClient;
  }

  /**
   * https://github.com/android/play-billing-samples/blob/main/TrivialDriveJava/app/src/main/java/com/sample/android/trivialdrivesample/billing/BillingDataSource.java#L358
   * @param billingResult
   * @param skuDetailsList
   */
  @Override
  public void onSkuDetailsResponse(@NonNull BillingResult billingResult,
                                   @Nullable List<SkuDetails> skuDetailsList) {
    int responseCode = billingResult.getResponseCode();
    String debugMessage = billingResult.getDebugMessage();
    switch (responseCode) {
      case BillingClient.BillingResponseCode.OK:
        Log.i(TAG, "onSkuDetailsResponse: " + responseCode + " " + debugMessage);
        Map<String, Object> res;
        if (skuDetailsList == null || skuDetailsList.isEmpty()) {
          Log.e(TAG, "onSkuDetailsResponse: " +
                  "Found null or empty SkuDetails. " +
                  "Check to see if the SKUs you requested are correctly published " +
                  "in the Google Play Console.");
          res = new HashMap<>();
        } else {
          // キャッシュチェック
          updateCachedSkus(skuDetailsList);
          res = skuDetailsListTranslator(skuDetailsList, billingResult);
        }
        if (res != null) {
          skuDetailsListLiveData.postValue(res);
        }
        break;
      case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
      case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
      case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
      case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
      case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
      case BillingClient.BillingResponseCode.ERROR:
        Log.e(TAG, "onSkuDetailsResponse: " + responseCode + " " + debugMessage);
        break;
      case BillingClient.BillingResponseCode.USER_CANCELED:
        Log.i(TAG, "onSkuDetailsResponse: " + responseCode + " " + debugMessage);
        break;
      // These response codes are not expected.
      case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
      case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
      case BillingClient.BillingResponseCode.ITEM_NOT_OWNED:
      default:
        Log.wtf(TAG, "onSkuDetailsResponse: " + responseCode + " " + debugMessage);
    }
    if (responseCode == BillingClient.BillingResponseCode.OK) {
      skuDetailsResponseTime = SystemClock.elapsedRealtime();
    } else {
      skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME;
    }
  }

  public LiveData<Map<String, Object>> getSkuDetailsList() {
    return skuDetailsListLiveData;
  }

  private Map<String, Object> skuDetailsListTranslator(
          List<SkuDetails> skuDetailsList,
          BillingResult billingResult) {
    final Map<String, Object> skuDetailsResponse = new HashMap<>();
    skuDetailsResponse.put("billingResult", Translator.fromBillingResult(billingResult));
    skuDetailsResponse.put("skuDetailsList", fromSkuDetailsList(skuDetailsList));
    return skuDetailsResponse;
  }

  /**
   * Calls the billing client functions to query sku details for both the inapp and subscription
   * SKUs. SKU details are useful for displaying item names and price lists to the user, and are
   * required to make a purchase.
   *
   * MethodCallHandlerImplから呼ぶ？
   */
  public void querySkuDetailsAsync(final String skuType, final List<String> skusList) {
    Log.d(TAG, skuType);
    Log.d(TAG, skusList.toString());
    billingClient.querySkuDetailsAsync(SkuDetailsParams.newBuilder()
            .setType(skuType)
            .setSkusList(skusList)
            .build(), this);
  }

  private void updateCachedSkus(@Nullable List<SkuDetails> skuDetailsList) {
    if (skuDetailsList == null) {
      return;
    }

    // 結局ここにfor文が来るのか
    for (SkuDetails skuDetails : skuDetailsList) {
      cachedSkus.put(skuDetails.getSku(), skuDetails);
    }
  }

  public HashMap<String, SkuDetails> getCachedSkus() {
    return cachedSkus;
  }
}
