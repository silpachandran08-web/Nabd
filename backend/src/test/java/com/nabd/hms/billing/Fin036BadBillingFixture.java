package com.nabd.hms.billing;

/** FIN-036 test fixture only: a billing class that depends on the insurance module, which Fin036InsuranceArchTest must catch. */
public class Fin036BadBillingFixture {
    com.nabd.hms.insurance.api.ClaimsGateway gateway;
}
