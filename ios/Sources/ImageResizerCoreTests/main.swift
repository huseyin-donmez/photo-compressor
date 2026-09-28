import Foundation

// Test entry point: `cd ios && swift run ImageResizerCoreTests`
// Exit code 0 = all green. (Zero-dependency harness; see TestKit.swift.)

private struct RegisteredTest {
    let name: String
    let body: () throws -> Void
}

private let allTests: [RegisteredTest] = [
    // Format strategy
    .init(name: "FormatStrategy.matrix", body: testFormatStrategyMatrix),
    .init(name: "FormatStrategy.unsupportedInputs", body: testFormatStrategyUnsupportedInputs),
    // Parameters
    .init(name: "Parameters.resolutionFloorScale", body: testResolutionFloorScaleSatisfiesBothConstraints),
    .init(name: "Parameters.memoryScale", body: testMemoryScale),
    .init(name: "Parameters.targetWithMargin", body: testTargetWithMargin),
    // Entitlement
    .init(name: "Entitlement.premiumIsUnlimited", body: testEntitlementPremiumIsUnlimited),
    .init(name: "Entitlement.planAllCreditsCoverBatch", body: testEntitlementPlanAllCreditsCoverBatch),
    .init(name: "Entitlement.planPartialBatchStopsAtCreditLimit", body: testEntitlementPlanPartialBatchStopsAtCreditLimit),
    .init(name: "Entitlement.planBlockedWhenNoCredits", body: testEntitlementPlanBlockedWhenNoCredits),
    .init(name: "Entitlement.consumePerSuccessOnly", body: testEntitlementConsumePerSuccessOnly),
    .init(name: "Entitlement.rewardedAdGrant", body: testEntitlementRewardedAdGrantsFiveAndNeverForPremium),
    // Algorithm: pass-through
    .init(name: "Algorithm.passThroughWithoutEncoding", body: testPassThroughWithoutEncoding),
    .init(name: "Algorithm.passThroughGrewFallsBackToEncode", body: testPassThroughGrewBeyondTargetFallsBackToEncode),
    .init(name: "Algorithm.passThroughUnavailableFallsBackToEncode", body: testPassThroughUnavailableFallsBackToEncode),
    // Algorithm: quality search
    .init(name: "Algorithm.qualitySearchFindsHighestFeasibleQuality", body: testQualitySearchFindsHighestFeasibleQuality),
    // Algorithm: resolution reduction
    .init(name: "Algorithm.resolutionReducedWhenQualityFloorFails", body: testResolutionReducedWhenQualityFloorCannotReachTarget),
    .init(name: "Algorithm.forcedFitMeetsPathologicalTarget", body: testForcedFitMeetsPathologicalTarget),
    .init(name: "Algorithm.targetBelowOneKBInfeasible", body: testTargetBelowOneKBIsInfeasible),
    // Algorithm: hard guarantee & budgets
    .init(name: "Algorithm.hardGuaranteeOverRandomizedInputs", body: testHardGuaranteeOverRandomizedInputs),
    .init(name: "Algorithm.decodeNeverExceedsMemoryBudget", body: testDecodeNeverExceedsMemoryBudget),
    .init(name: "Algorithm.cancellationThrowsBetweenIterations", body: testCancellationThrowsBetweenIterations),
    .init(name: "Algorithm.unsupportedFormatThrowsBeforeAnyWork", body: testUnsupportedFormatThrowsBeforeAnyWork),
    .init(name: "Algorithm.alphaPNGKeepsPNGAndReducesPixelsOnly", body: testAlphaPNGKeepsPNGAndReducesPixelsOnly),
    // Integration (real ImageIO + fixtures)
    .init(name: "Integration.smoothJPEG12MBto5MB", body: testSmoothJPEG12MBTo5MBKeepsFullResolution),
    .init(name: "Integration.noiseJPEGto1MB", body: testNoiseJPEGTo1MBAlwaysUnderTarget),
    .init(name: "Integration.smallJPEGPassThrough", body: testSmallJPEGPassesThroughWithoutReencode),
    .init(name: "Integration.flatPNGPassThrough", body: testFlatPNGPassesThroughKeepingPNGContainer),
    .init(name: "Integration.portraitReencodeOrientationAndGPS", body: testPortraitOrientationReencodeBakesOrientationAndStripsGPS),
    .init(name: "Integration.portraitPassThroughGPSAndOrientation", body: testPortraitPassThroughStripsGPSKeepsOrientation),
    .init(name: "Integration.photoPNGtoJPEGKeepingResolution", body: testPhotoPNGWithoutAlphaConvertsToJPEGKeepingResolution),
    .init(name: "Integration.transparentPNGStaysPNG", body: testTransparentPNGStaysPNGReducesPixelsToFit),
    .init(name: "Integration.heicToSmallerHEIC", body: testHEICToSmallerHEIC),
    .init(name: "Integration.portraitHEICUprightAndGPSFree", body: testPortraitHEICUprightAndGPSFree),
    .init(name: "Integration.opaqueWebPtoJPEG", body: testOpaqueWebPConvertsToJPEG),
    .init(name: "Integration.transparentWebPtoPNG", body: testTransparentWebPBecomesPNG),
    .init(name: "Integration.animatedGIFtoJPEG", body: testAnimatedGIFFirstFrameToJPEG),
    .init(name: "Integration.corruptFileThrows", body: testCorruptFileThrowsWithoutSuccess),
    .init(name: "Integration.emptyFileThrows", body: testEmptyFileThrowsCorrupted),
    .init(name: "Integration.diskOutputRejectsOversized", body: testDiskOutputRejectsOversizedDataAndDeletesFile),
    .init(name: "Integration.batchTwoInputs", body: testBatchTwoInputsProduceVerifiedOutputs),
]

var passed = 0
var failed = 0
var skipped = 0
var failures: [(String, [String])] = []

for test in allTests {
    RunContext.shared = RunContext()
    do {
        try test.body()
        if RunContext.shared.failures.isEmpty {
            passed += 1
            print("✓ \(test.name)")
        } else {
            failed += 1
            failures.append((test.name, RunContext.shared.failures))
            print("✗ \(test.name)")
        }
    } catch let skip as TestSkipped {
        skipped += 1
        print("○ \(test.name) — SKIPPED: \(skip.description)")
    } catch {
        failed += 1
        failures.append((test.name, ["threw \(error)"]))
        print("✗ \(test.name) — threw \(error)")
    }
}

if !failures.isEmpty {
    print("\nFailures:")
    for (name, messages) in failures {
        for message in messages {
            print("  \(name): \(message)")
        }
    }
}

print("\n\(passed) passed, \(failed) failed, \(skipped) skipped (of \(allTests.count))")
exit(failed == 0 ? 0 : 1)
