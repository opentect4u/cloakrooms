package com.synergic.cloakroom.data

/** The customer (tenant) whose data this device works with. */
object Tenant {
    /**
     * The logged-in user's customer_id from the server login (API_GUIDE.md §3); 1 (the demo data)
     * until the first login. Set by the auth repository on login and on app start.
     */
    @Volatile
    var CUSTOMER_ID: Long = 1L

    /** created_by / updated_by for rows written by the app itself (seed data). */
    const val SYSTEM_USER = "SYSTEM"
}
