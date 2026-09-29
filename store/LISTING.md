# Store submission kit — Photo Compressor KB & MB

Single worksheet for the Play Console + App Store Connect walkthroughs.
Copy-paste blocks are ready; **bold REPLACE** items are the only things I
can't know.

---

## Identity

| Field | Value |
|---|---|
| Store title (both stores, ≤30 chars) | `Photo Compressor KB & MB` (24) |
| Launcher label (Android) | `Photo Compressor` |
| Package / bundle | `com.imageresizer.app` / iOS id TBD at App Store Connect |
| Version | `1.0` (versionCode `1`) |
| Pricing | Free; ads; one-time **$5** unlock (`lifetime`) |

---

## Play Store listing copy

### Short description (≤80 chars, 79 used)

```
Compress photos to any size limit. Batch, on-device, private. No uploads.
```

### Full description (≤4000 chars)

```
Photo Compressor KB & MB shrinks photos under a size limit you choose —
and guarantees the output stays under it.

HOW IT WORKS
• Pick ONE maximum size — a scroll-wheel target in KB or MB (try 100 KB
  for email, 2 MB for messaging, 5 MB default).
• Choose as many photos as you want (tested with hundreds — batch runs
  of 500+ are fine).
• Tap Resize. Every saved image is verified on disk to be at or under
  your limit.

SMART COMPRESSION, IN THIS ORDER
1. Keep full resolution — no shrinking unless necessary
2. Optimize encoding quality
3. Reduce resolution only if quality alone can't reach the target
4. Verify the file really is under the limit before saving

PRIVATE BY DESIGN
• 100% on-device processing — photos are NEVER uploaded. The app works
  offline; internet is used only for ads and purchases.
• GPS location tags are stripped from exported photos.
• Camera orientation is baked in, so results look right everywhere.
• Already-small photos pass through untouched — no wasted credits.

FREE, FAIR MONETIZATION
• 5 free compression credits to start.
• Photos that already fit the limit are always free.
• Watch an optional ad for +5 credits.
• Or unlock lifetime for $5: unlimited compression, all ads removed,
  one-time payment, no subscription.

Share photos directly into the app from any gallery, and share results
straight back out. Dark mode and Material You theming included.

Your photos stay yours. Compress with peace of mind.
```

---

## Play Console — form answers

### Data safety form (draft; verify against AdMob's own data-safety snippet when the real SDK IDs are in)

- Collects/shares **nothing** from you directly: no personal info, no
  location, no photos, no messages, no device logs.
- **Data collected by third parties (AdMob):**
  - `Device or other IDs` — collected ✅, shared for advertising ✅
  - `App interactions` (ad views/clicks) — collected ✅, shared ✅
  - `IP address / approximate location` — collected ✅ (AdMob derives
    coarse location from IP), shared ✅
- **Data is encrypted in transit** ✅ (SDK traffic; HTTPS)
- **Users can request deletion**: ✅ "N/A — no data is collected by the
  developer" is the honest option for our own data; the third-party row
  gets the standard AdMob answer.
- ✅ "Data is never sold" (Play's ad-partner clause still required for
  ads: check Google's standard wording in the wizard).

### Ads declaration

- App **contains ads** ✅ (rewarded + banner) · not based on ads alone.

### Content rating questionnaire

- Category: Photo/video tools · Violence: none · Mature content: none
- Ads: contain ads ✅, no ads for alcohol/gambling etc.
- Expected rating: **PEGI 3 / ESRB Everyone** (IARC).

### Target audience

- Ages: **13+** (ads-supported general utility, no child appeal, no
  family policy) · NOT a family app.

### IAP product (Play Console → Monetize → Products → In-app products)

| Field | Value |
|---|---|
| Product ID | `lifetime` (must match `PRODUCT_LIFETIME`) |
| Type | One-time (managed, non-consumable) |
| Price | **$5.00** (localize for other markets Play suggests) |
| Title | `Lifetime unlock` |
| Description | `Unlimited compression, all ads removed. One-time purchase.` |

### Closed testing (personal account rule)

1. Testers → Closed testing → Create track.
2. Upload the AAB (`app-release.aab`).
3. Add **12 tester emails** (they must opt in via the invite link and stay
   opted-in **14 consecutive days**).
4. Keep the build fresh (re-upload on updates). After 14 days apply for
   production access in the same dashboard.

---

## Screenshots — CAPTURED ✅ (2026-09-29)

Pixel 8 AVD, 1080×2400 (≥ Play's 1080 minimum), ads suppressed (airplane
mode), free-tier UI with full monetization row visible. In `store/screenshots/`
— **upload in this order**:

1. `01-home-100kb.png` — Home, wheel at **100 KB**, Credits: 20, `$5` CTA
2. `02-selection.png` — 6 colorful thumbnails + `Resize 6 photos`
3. `03-running.png` — mid-batch: progress bar **3 of 6**, saved/running/waiting
4. `04-done.png` — `6 of 6 photos saved under 100 KB` + `Share 6` + sizes
5. `05-dark-home.png` — dark theme home @ 100 KB

(Share-sheet shot skipped — 5 shots already in the ideal 4–6 range.)

Regenerate anytime: boot the AVD with the 8 sample photos already in
MediaStore and `python3 scripts/store_screenshots.py` (needs the debug APK
installed; it resets credits, cleans MediaStore pollution, and drives the UI).

---

## Feature graphic / icons (already generated ✅)

- `store/icon-512.png` — Play icon (512×512)
- `store/feature-graphic.png` — Play feature graphic (1024×500)
- `store/icon-1024.png` — App Store icon (1024×1024, opaque)
- Regenerate anytime: `python3 scripts/make_store_assets.py`

---

## IDs & accounts to swap before publishing

| Placeholder (test IDs today) | Where | Your action |
|---|---|---|
| AdMob **app ID** `…~3347511713` | `android/app/build.gradle.kts` `manifestPlaceholders` | Create AdMob account → apps → add app |
| **Rewarded** unit `…/5224354917` | same file, `REWARDED_AD_UNIT_ID` | AdMob → ad units → Rewarded |
| **Banner** unit `…/6300978111` | same file, `BANNER_AD_UNIT_ID` | AdMob → ad units → Banner |
| Contact email in privacy policy | `docs/privacy-policy.html` | ✅ `appkitstudios@gmail.com` |

Everything else (billing product `lifetime`, keystore) is already wired.

---

## Privacy policy (live ✅)

- **URL for Play Console / App Store Connect:**
  `https://huseyin-donmez.github.io/photo-compressor/privacy-policy.html`
- Source: `docs/privacy-policy.html` (Pages serves the `/docs` folder of
  `github.com/huseyin-donmez/photo-compressor`, public).
- ✅ Contact email set inside that file → `appkitstudios@gmail.com`
  (committed & pushed; Pages rebuilds in ~1 minute).

---

## App Store (Track B) — quick notes

- **Name:** `Photo Compressor KB & MB` · **Subtitle (≤30):** `Compress to
  any size limit` · **Keywords (≤100):**
  `photo,compressor,image,resizer,resize,kb,mb,compress,picture,reduce`
- Privacy labels: Ads data (device ID, usage data) via AdMob; purchases
  handled by Apple/StoreKit — no data you collect yourself.
- Screenshots: 6.9" + 6.5" (simulator), feature graphic not needed.
- Export compliance: `ordinary rules exempt` (no custom encryption).
