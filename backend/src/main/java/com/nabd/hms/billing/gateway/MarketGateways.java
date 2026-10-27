package com.nabd.hms.billing.gateway;

import org.springframework.stereotype.Component;

/** FIN-019.4: Tap (KSA/UAE) and HyperPay (KSA) sit behind the same port but are switched off until FIN-024 builds them. */
final class MarketGateways {

    private MarketGateways() {
    }

    abstract static class Disabled implements PaymentGateway {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String currency() {
            return "SAR";
        }

        @Override
        public void verify(Credentials credentials) {
            throw GatewayException.unsupported(provider());
        }
    }

    @Component
    static class Tap extends Disabled {
        @Override
        public String provider() {
            return "tap";
        }
    }

    @Component
    static class HyperPay extends Disabled {
        @Override
        public String provider() {
            return "hyperpay";
        }
    }
}
