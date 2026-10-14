import Testing
import AsyncChannel

@Suite struct AsyncChannelExportTests {
    @Test func testSwiftModuleLoads() throws {
        #expect(Bool(true), "AsyncChannel swift module imported cleanly")
    }
}


