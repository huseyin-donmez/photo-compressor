import Foundation

// A minimal, zero-dependency test harness with XCTest-compatible assertion names,
// so the suite can migrate to XCTest/Swift Testing unchanged once Xcode is installed.
// (This machine has Command Line Tools only; neither XCTest nor swift-testing can
// discover tests there.)

struct TestFailure: Error, CustomStringConvertible {
    let description: String
}

struct TestSkipped: Error, CustomStringConvertible {
    let description: String
}

final class RunContext {
    nonisolated(unsafe) static var shared = RunContext()
    var failures: [String] = []
}

private func record(_ message: String, _ detail: String,
                    file: StaticString, line: UInt) {
    let location = "\(file):\(line)"
    RunContext.shared.failures.append("\(message.isEmpty ? detail : message + " — " + detail) [\(location)]")
}

func skip(_ message: String) throws -> TestSkipped {
    TestSkipped(description: message)
}

func skipUnless(_ condition: Bool, _ message: String) throws {
    if !condition { throw TestSkipped(description: message) }
}

@discardableResult
func XCTAssert(_ condition: @autoclosure () -> Bool,
               _ message: @autoclosure () -> String = "",
               file: StaticString = #filePath, line: UInt = #line) -> Bool {
    if condition() { return true }
    record(message(), "assertion failed", file: file, line: line)
    return false
}

@discardableResult
func XCTAssertTrue(_ condition: @autoclosure () -> Bool,
                   _ message: @autoclosure () -> String = "",
                   file: StaticString = #filePath, line: UInt = #line) -> Bool {
    XCTAssert(condition(), message(), file: file, line: line)
}

@discardableResult
func XCTAssertFalse(_ condition: @autoclosure () -> Bool,
                    _ message: @autoclosure () -> String = "",
                    file: StaticString = #filePath, line: UInt = #line) -> Bool {
    XCTAssert(!condition(), message(), file: file, line: line)
}

func XCTAssertEqual<T: Equatable>(_ expression1: @autoclosure () throws -> T,
                                  _ expression2: @autoclosure () throws -> T,
                                  _ message: @autoclosure () -> String = "",
                                  file: StaticString = #filePath, line: UInt = #line) {
    do {
        let (a, b) = (try expression1(), try expression2())
        if a == b { return }
        record(message(), "\(a) is not equal to \(b)", file: file, line: line)
    } catch {
        record(message(), "threw \(error)", file: file, line: line)
    }
}

func XCTAssertEqual<T: FloatingPoint>(_ expression1: @autoclosure () throws -> T,
                                      _ expression2: @autoclosure () throws -> T,
                                      accuracy: T,
                                      _ message: @autoclosure () -> String = "",
                                      file: StaticString = #filePath, line: UInt = #line) {
    do {
        let (a, b) = (try expression1(), try expression2())
        if abs(a - b) <= accuracy { return }
        record(message(), "\(a) is not equal to \(b) within \(accuracy)", file: file, line: line)
    } catch {
        record(message(), "threw \(error)", file: file, line: line)
    }
}

func XCTAssertNil(_ expression: @autoclosure () throws -> Any?,
                  _ message: @autoclosure () -> String = "",
                  file: StaticString = #filePath, line: UInt = #line) {
    do {
        if try expression() == nil { return }
        record(message(), "expected nil", file: file, line: line)
    } catch {
        record(message(), "threw \(error)", file: file, line: line)
    }
}

func XCTAssertNotNil(_ expression: @autoclosure () throws -> Any?,
                     _ message: @autoclosure () -> String = "",
                     file: StaticString = #filePath, line: UInt = #line) {
    do {
        if try expression() != nil { return }
        record(message(), "expected non-nil", file: file, line: line)
    } catch {
        record(message(), "threw \(error)", file: file, line: line)
    }
}

func XCTAssertLessThan<T: Comparable>(_ a: @autoclosure () throws -> T,
                                      _ b: @autoclosure () throws -> T,
                                      _ message: @autoclosure () -> String = "",
                                      file: StaticString = #filePath, line: UInt = #line) {
    comparison(a, b, message(), file: file, line: line) { $0 < $1 }
}

func XCTAssertLessThanOrEqual<T: Comparable>(_ a: @autoclosure () throws -> T,
                                             _ b: @autoclosure () throws -> T,
                                             _ message: @autoclosure () -> String = "",
                                             file: StaticString = #filePath, line: UInt = #line) {
    comparison(a, b, message(), file: file, line: line) { $0 <= $1 }
}

func XCTAssertGreaterThan<T: Comparable>(_ a: @autoclosure () throws -> T,
                                         _ b: @autoclosure () throws -> T,
                                         _ message: @autoclosure () -> String = "",
                                         file: StaticString = #filePath, line: UInt = #line) {
    comparison(a, b, message(), file: file, line: line) { $0 > $1 }
}

func XCTAssertGreaterThanOrEqual<T: Comparable>(_ a: @autoclosure () throws -> T,
                                                _ b: @autoclosure () throws -> T,
                                                _ message: @autoclosure () -> String = "",
                                                file: StaticString = #filePath, line: UInt = #line) {
    comparison(a, b, message(), file: file, line: line) { $0 >= $1 }
}

private func comparison<T: Comparable>(_ a: () throws -> T, _ b: () throws -> T,
                                       _ message: String,
                                       file: StaticString, line: UInt,
                                       _ ok: (T, T) -> Bool) {
    do {
        let (lhs, rhs) = (try a(), try b())
        if ok(lhs, rhs) { return }
        record(message, "comparison failed: \(lhs) vs \(rhs)", file: file, line: line)
    } catch {
        record(message, "threw \(error)", file: file, line: line)
    }
}

func XCTFail(_ message: @autoclosure () -> String = "",
             file: StaticString = #filePath, line: UInt = #line) {
    record(message(), "failed", file: file, line: line)
}

func XCTUnwrap<T>(_ expression: @autoclosure () throws -> T?,
                  _ message: @autoclosure () -> String = "",
                  file: StaticString = #filePath, line: UInt = #line) throws -> T {
    guard let value = try expression() else {
        throw TestFailure(description: "\(message().isEmpty ? "unexpectedly nil" : message()) [\(file):\(line)]")
    }
    return value
}

func XCTAssertThrowsError<T>(_ expression: @autoclosure () throws -> T,
                             _ message: @autoclosure () -> String = "",
                             file: StaticString = #filePath, line: UInt = #line,
                             _ errorHandler: (_ error: Error) throws -> Void = { _ in }) {
    do {
        _ = try expression()
        record(message(), "expected an error but none was thrown", file: file, line: line)
    } catch {
        do {
            try errorHandler(error)
        } catch {
            record(message(), "error handler threw \(error)", file: file, line: line)
        }
    }
}
