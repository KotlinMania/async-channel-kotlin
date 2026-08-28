import Testing
import AsyncChannel

@Suite struct AsyncChannelExportTests {
    @Test func testSwiftModuleLoads() throws {
        let pair = unbounded()
        #expect(pair.first != nil)
    }
}


