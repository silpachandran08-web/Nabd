# Nabd product prototype

A clickable, end-product-scope prototype of the Nabd platform: every role from a solo doctor to a hospital chain, the pharmacy and lab (also sold standalone), billing and insurance, the patient app, WhatsApp and the public booking site. It follows the Nabd design system (`DESIGN.md` in the original prototype: dark-first, Poppins, cyan-mint accent, one monoline icon family).

All data is fictional sample data. Nothing talks to the backend.

## Run it

Open `index.html` through any static server, for example:

```bash
cd prototype && python3 -m http.server 8080   # then open http://localhost:8080
```

Use the bar at the top to switch **who you are viewing as**, the **market** (India, UAE, Saudi Arabia, Qatar: currency, tax, national ID, exchanges), the **facility model** (solo, clinic, polyclinic, hospital), **device** and **language** (English, Hindi, Arabic with right-to-left layout). `Ctrl K` jumps to any screen. Deep links use the hash, e.g. `#pharmacist.stock`.

## Structure

```
prototype/
  index.html            page shell, loads scripts in order
  css/nb.css            design tokens and every component style
  js/icons.js           SVG icon sprite (ids: i-<name>, see NB_ICONS)
  js/data.js            shared sample data + markets + facility types
  js/core.js            state, roles & navigation, registries, shell, overlays, actions
  js/modules/*.js       one file per product module (screens + overlays + actions)
```

## How a module adds screens (conventions)

Screens are plain functions that return an HTML string. No framework, no build step.

```js
(function () {
  var ui = NB.ui, L = NB.L;
  // Screen id = "<role>.<view>" and must match a nav entry in NB.ROLES (core.js)
  NB.screen("pharmacist.stock", function (c) {
    // c = { s: state, L, ui, m: market, D: data, rtl, fac, facType, money, mobile, role, v }
    return ui.header(L("Stock & expiry", "المخزون والصلاحية", "स्टॉक और एक्सपायरी"), "subtitle") + "...";
  });
  // Drawers and dialogs. Open with data-ov="name" data-arg="..."
  NB.overlay("batchdetail", function (arg, c) {
    return { kind: "drawer" /* or "modal" */, size: "lg", title: "Batch " + arg, body: "...", foot: ui.btn("Close", { act: "close" }) };
  });
  // Actions. Fire with data-act="name" data-arg="..."
  NB.act("dispense", function (arg) { NB.toast("Dispensed " + arg); });
})();
```

Declarative attributes handled by core:

| Attribute | Effect |
|---|---|
| `data-go="role.view"` | Navigate to a screen |
| `data-ov="name" data-arg="x"` | Open an overlay |
| `data-act="name" data-arg="x"` | Run an action (`close`, `toast` are built in) |
| `data-set="key" data-val="v"` | Set a UI state key and re-render (tabs, segments, radios) |
| `data-toggle="key"` | Flip a boolean UI key (switches) |
| `data-change="key"` on a `<select>` | Store its value in UI state |

Read UI state with `NB.v(key, default)`; set it with `NB.setUI({key: value})`. Helpers in `NB.ui`: `header, card, table, cellp, pill, btn, seg, tabs, toggle, radio, field, note, li, kpi, av, bar, icon, esc`. Money: `NB.money(amountInInr)` converts and formats for the selected market. Market facts: `NB.mkt()` (tax name/rate, national ID, exchange, claims, e-invoice). Records: `NB.pat(id)`, `NB.doc(id)`, `NB.drug(id)`.

Rules: every status pill has an icon and text; numbers use `num` (and `ltr` inside RTL text); use logical CSS properties so Arabic mirrors; page titles and nav labels are given in English, Arabic and Hindi via `L()`; staff screens must work at desktop, tablet and mobile widths (tables sit inside `.tw` which scrolls on its own).
