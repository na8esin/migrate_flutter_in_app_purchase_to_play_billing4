package io.flutter.plugins.inapppurchase;

import android.content.Context;
import com.android.billingclient.api.BillingClient;
import io.flutter.plugin.common.MethodChannel;

/** The implementation for {@link BillingClientFactory} for the plugin. */
final class BillingClientFactoryImpl implements BillingClientFactory {

  private static final String TAG = "BillingClientFactory";

  @Override
  public BillingClient createBillingClient(
      Context context, MethodChannel channel, boolean enablePendingPurchases) {
    BillingClient.Builder builder = BillingClient.newBuilder(context);
    if (enablePendingPurchases) {
      builder.enablePendingPurchases();
    }
    return builder.setListener(new PluginPurchaseListener(channel)).build();
  }
}
