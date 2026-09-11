# Release Checklist

## Phase 1 — Core
- [ ] Final package/application ID frozen before first Play upload
- [ ] Native lock engine verified on physical devices
- [ ] PIN / biometric / pattern manually verified
- [ ] No-flash/recent-task privacy verified
- [ ] Accessibility disclosure + declaration verified
- [ ] Reboot recovery verified

## Phase 2 — Premium UX
- [ ] Central theme system verified across screens
- [ ] Light/Dark/System verified
- [ ] Cosmic Orb / Liquid Flow / Pro animation behavior verified
- [ ] Reduced motion verified
- [ ] Responsive layouts verified

## Phase 3 — Hardening
- [ ] OEM battery/background behavior verified
- [ ] Protection Health states truthful
- [ ] No sensitive logs
- [ ] No broad package visibility permission
- [ ] Release minification verified

## Phase 4 — Monetization
- [ ] Play subscription product and offer configured
- [ ] Billing restore/pending/cancel paths verified
- [ ] UMP consent flow verified where applicable
- [ ] AdMob app confirmed/readiness completed
- [ ] Ad placement reviewed separately from authentication

## Phase 5 — Release engineering
- [ ] Upload key stored outside repository / CI secrets
- [ ] Version code increments for every Play upload
- [ ] Privacy Policy URL live
- [ ] Data Safety answers match implementation
- [ ] Closed test completed
- [ ] Play pre-launch/device reports reviewed
- [ ] Production rollout only after manual sign-off
