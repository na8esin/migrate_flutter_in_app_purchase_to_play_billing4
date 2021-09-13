package io.flutter.plugins.inapppurchase;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.Map;

public class BillingClientViewModel extends ViewModel {

    private final BillingClientFactory bcf;

    public BillingClientViewModel(@NonNull BillingClientFactory billingClientFactory) {
        super();
        bcf = billingClientFactory;
    }

    public LiveData<Map<String, Object>> getSkuDetailsList() {
        return bcf.getSkuDetailsList();
    }

    public static class BillingClientViewModelFactory implements ViewModelProvider.Factory {
        private final BillingClientFactory billingClientFactory;

        public BillingClientViewModelFactory(BillingClientFactory bcf) {
            billingClientFactory = bcf;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(BillingClientViewModel.class)) {
                return (T) new BillingClientViewModel(billingClientFactory);
            }
            throw new IllegalArgumentException("Unknown ViewModel class");
        }
    }
}
