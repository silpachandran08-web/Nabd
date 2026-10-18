package com.nabd.hms.common;

/** Mirrors the ModuleGrant schema in api/openapi.yaml — one row of the RBAC Matrix tab. */
public record ModuleGrant(
        String module,
        boolean view,
        boolean create,
        boolean edit,
        boolean delete,
        boolean approve,
        boolean refundDiscount,
        boolean export,
        boolean breakGlass // PLT-012: only meaningful on module clinical -> clinical:breakGlass
) {
    public ModuleGrant(String module, boolean view, boolean create, boolean edit, boolean delete, boolean approve,
                       boolean refundDiscount, boolean export) {
        this(module, view, create, edit, delete, approve, refundDiscount, export, false);
    }
}
