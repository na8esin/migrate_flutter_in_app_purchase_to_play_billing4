// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.inapppurchase;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsResponseListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.MethodChannel;

/** The implementation for {@link BillingClientFactory} for the plugin. */
final class BillingClientFactoryImpl implements BillingClientFactory, SkuDetailsResponseListener {

  private static final String TAG = "BillingClientFactory";
  private static final long SKU_DETAILS_REQUERY_TIME = 1000L * 60L * 60L * 4L; // 4 hours
  final private Map<String, MutableLiveData<SkuDetails>> skuDetailsLiveDataMap = new HashMap<>();
  // when was the last successful SkuDetailsResponse?
  private long skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME;

  @Override
  public BillingClient createBillingClient(
      Context context, MethodChannel channel, boolean enablePendingPurchases) {
    BillingClient.Builder builder = BillingClient.newBuilder(context);
    if (enablePendingPurchases) {
      builder.enablePendingPurchases();
    }
    return builder.setListener(new PluginPurchaseListener(channel)).build();
  }

  /**
   * https://github.com/android/play-billing-samples/blob/main/TrivialDriveJava/app/src/main/java/com/sample/android/trivialdrivesample/billing/BillingDataSource.java#L358
   * @param billingResult
   * @param list
   */
  @Override
  public void onSkuDetailsResponse(@NonNull BillingResult billingResult,
                                   @Nullable List<SkuDetails> skuDetailsList) {
    int responseCode = billingResult.getResponseCode();
    String debugMessage = billingResult.getDebugMessage();
    switch (responseCode) {
      case BillingClient.BillingResponseCode.OK:
        Log.i(TAG, "onSkuDetailsResponse: " + responseCode + " " + debugMessage);
        if (skuDetailsList == null || skuDetailsList.isEmpty()) {
          Log.e(TAG, "onSkuDetailsResponse: " +
                  "Found null or empty SkuDetails. " +
                  "Check to see if the SKUs you requested are correctly published " +
                  "in the Google Play Console.");
        } else {
          for (SkuDetails skuDetails : skuDetailsList) {
            String sku = skuDetails.getSku();
            MutableLiveData<SkuDetails> detailsMutableLiveData =
                    skuDetailsLiveDataMap.get(sku);
            if (null != detailsMutableLiveData) {
              detailsMutableLiveData.postValue(skuDetails);
            } else {
              Log.e(TAG, "Unknown sku: " + sku);
            }
          }
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
}
