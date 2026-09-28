import ImageResizerCore

func testFormatStrategyMatrix() {
    XCTAssertEqual(FormatStrategy.outputFormat(source: .jpeg, hasAlpha: false), .jpeg)
    XCTAssertEqual(FormatStrategy.outputFormat(source: .heic, hasAlpha: false), .heic)
    // Photo PNG without alpha converts to JPEG (preserve resolution is priority #1).
    XCTAssertEqual(FormatStrategy.outputFormat(source: .png, hasAlpha: false), .jpeg)
    // Alpha must stay lossless.
    XCTAssertEqual(FormatStrategy.outputFormat(source: .png, hasAlpha: true), .png)
    // iOS has no WebP encoder: JPEG normally, PNG when transparency must survive.
    XCTAssertEqual(FormatStrategy.outputFormat(source: .webp, hasAlpha: false), .jpeg)
    XCTAssertEqual(FormatStrategy.outputFormat(source: .webp, hasAlpha: true), .png)
    XCTAssertEqual(FormatStrategy.outputFormat(source: .gif, hasAlpha: false), .jpeg)
    XCTAssertEqual(FormatStrategy.outputFormat(source: .gif, hasAlpha: true), .png)
    XCTAssertEqual(FormatStrategy.outputFormat(source: .bmp, hasAlpha: false), .jpeg)
}

func testFormatStrategyUnsupportedInputs() {
    XCTAssertNil(FormatStrategy.outputFormat(source: .tiff, hasAlpha: false))
    XCTAssertNil(FormatStrategy.outputFormat(source: .raw, hasAlpha: false))
    XCTAssertNil(FormatStrategy.outputFormat(source: .other, hasAlpha: false))
}

func testResolutionFloorScaleSatisfiesBothConstraints() {
    let p = ResizeParameters.standard
    // 4000×3000 → 0.256 gives exactly 1024×768: longest ≥1024, min ≥512.
    let scale = p.resolutionFloorScale(width: 4000, height: 3000)
    XCTAssertEqual(scale, 1024.0 / 4000.0, accuracy: 0.0001)
    XCTAssertGreaterThanOrEqual(Int(4000 * scale), 1024)
    XCTAssertGreaterThanOrEqual(Int(3000 * scale), 512)

    // Small images are already below the floor → no voluntary reduction.
    XCTAssertEqual(p.resolutionFloorScale(width: 800, height: 600), 1.0)
    XCTAssertEqual(p.resolutionFloorScale(width: 640, height: 480), 1.0)
}

func testMemoryScale() {
    var p = ResizeParameters.standard
    p.maxDecodePixels = 4_000_000
    XCTAssertEqual(p.memoryScale(width: 1000, height: 1000), 1.0)
    let scale = p.memoryScale(width: 4000, height: 3000)
    XCTAssertEqual(scale, (4_000_000.0 / 12_000_000.0).squareRoot(), accuracy: 0.0001)
    XCTAssertLessThanOrEqual(Double(4000 * 3000) * scale * scale, 4_000_001)
}

func testTargetWithMargin() {
    XCTAssertEqual(ResizeParameters.standard.targetWithMargin(5_000_000), 4_850_000)
    XCTAssertEqual(ResizeParameters.standard.targetWithMargin(100 * 1024),
                   Int64(Double(100 * 1024) * 0.97))
}

func testEntitlementPremiumIsUnlimited() {
    var state = UsageState(remainingFreeCredits: 0, isPremium: true)
    XCTAssertEqual(Entitlement.plan(neededWork: 500, state: state), .unlimited)
    // Credits never move for premium.
    Entitlement.consumeCredit(state: &state)
    XCTAssertEqual(state.remainingFreeCredits, 0)
}

func testEntitlementPlanAllCreditsCoverBatch() {
    let state = UsageState(remainingFreeCredits: 5)
    XCTAssertEqual(Entitlement.plan(neededWork: 3, state: state), .allowed(neededWork: 3))
    XCTAssertEqual(Entitlement.plan(neededWork: 5, state: state), .allowed(neededWork: 5))
    XCTAssertEqual(Entitlement.plan(neededWork: 0, state: state),
                   .allowed(neededWork: 0), "all-pass-through batches are free")
}

func testEntitlementPlanPartialBatchStopsAtCreditLimit() {
    let state = UsageState(remainingFreeCredits: 3)
    XCTAssertEqual(Entitlement.plan(neededWork: 20, state: state),
                   .partial(allowedWork: 3))
}

func testEntitlementPlanBlockedWhenNoCredits() {
    let state = UsageState(remainingFreeCredits: 0)
    XCTAssertEqual(Entitlement.plan(neededWork: 1, state: state), .blocked)
}

func testEntitlementConsumePerSuccessOnly() {
    var state = UsageState(remainingFreeCredits: 2)
    Entitlement.consumeCredit(state: &state)
    XCTAssertEqual(state.remainingFreeCredits, 1)
    Entitlement.consumeCredit(state: &state)
    Entitlement.consumeCredit(state: &state) // extra success (shouldn't happen) clamps
    XCTAssertEqual(state.remainingFreeCredits, 0)
}

func testEntitlementRewardedAdGrantsFiveAndNeverForPremium() {
    var state = UsageState(remainingFreeCredits: 0)
    Entitlement.grantRewardedAd(state: &state)
    XCTAssertEqual(state.remainingFreeCredits, ProductRules.rewardedAdGrantCount)

    var premium = UsageState(remainingFreeCredits: 0, isPremium: true)
    Entitlement.grantRewardedAd(state: &premium)
    XCTAssertEqual(premium.remainingFreeCredits, 0)
}
