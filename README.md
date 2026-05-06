# async-channel-kotlin in Kotlin

[![GitHub link](https://img.shields.io/badge/GitHub-KotlinMania%2Fasync--channel--kotlin-blue.svg)](https://github.com/KotlinMania/async-channel-kotlin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/async-channel-kotlin)](https://central.sonatype.com/artifact/io.github.kotlinmania/async-channel-kotlin)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/async-channel-kotlin/ci.yml?branch=main)](https://github.com/KotlinMania/async-channel-kotlin/actions)

This is a Kotlin Multiplatform line-by-line transliteration port of [`smol-rs/async-channel`](https://github.com/smol-rs/async-channel).

**Original Project:** This port is based on [`smol-rs/async-channel`](https://github.com/smol-rs/async-channel). All design credit and project intent belong to the upstream authors; this repository is a faithful port to Kotlin Multiplatform with no behavioural changes intended.

### Porting status

This is an **in-progress port**. The goal is feature parity with the upstream Rust crate while providing a native Kotlin Multiplatform API. Every Kotlin file carries a `// port-lint: source <path>` header naming its upstream Rust counterpart so the AST-distance tool can track provenance.

---

## Upstream README — `smol-rs/async-channel`

> The text below is reproduced and lightly edited from [`https://github.com/smol-rs/async-channel`](https://github.com/smol-rs/async-channel). It is the upstream project's own description and remains under the upstream authors' authorship; links have been rewritten to absolute upstream URLs so they continue to resolve from this repository.

## async-channel

[![Build](https://github.com/smol-rs/async-channel/actions/workflows/ci.yml/badge.svg)](
https://github.com/smol-rs/async-channel/actions)
[![License](https://img.shields.io/badge/license-Apache--2.0_OR_MIT-blue.svg)](
https://github.com/smol-rs/async-channel)
[![Cargo](https://img.shields.io/crates/v/async-channel.svg)](
https://crates.io/crates/async-channel)
[![Documentation](https://docs.rs/async-channel/badge.svg)](
https://docs.rs/async-channel)

An async multi-producer multi-consumer channel, where each message can be received by only
one of all existing consumers.

There are two kinds of channels:

1. Bounded channel with limited capacity.
2. Unbounded channel with unlimited capacity.

A channel has the `Sender` and `Receiver` side. Both sides are cloneable and can be shared
among multiple threads.

When all `Sender`s or all `Receiver`s are dropped, the channel becomes closed. When a
channel is closed, no more messages can be sent, but remaining messages can still be received.

The channel can also be closed manually by calling `Sender::close()` or
`Receiver::close()`.

## Examples

```rust
let (s, r) = async_channel::unbounded();

assert_eq!(s.send("Hello").await, Ok(()));
assert_eq!(r.recv().await, Ok("Hello"));
```

## License

Licensed under either of

 * Apache License, Version 2.0 ([LICENSE-APACHE](https://github.com/smol-rs/async-channel/blob/HEAD/LICENSE-APACHE) or http://www.apache.org/licenses/LICENSE-2.0)
 * MIT license ([LICENSE-MIT](https://github.com/smol-rs/async-channel/blob/HEAD/LICENSE-MIT) or http://opensource.org/licenses/MIT)

at your option.

#### Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in the work by you, as defined in the Apache-2.0 license, shall be
dual licensed as above, without any additional terms or conditions.

---

## About this Kotlin port

### Installation

```kotlin
dependencies {
    implementation("io.github.kotlinmania:async-channel-kotlin:0.1.0-SNAPSHOT")
}
```

### Building

```bash
./gradlew build
./gradlew test
```

### Targets

- macOS arm64
- Linux x64
- Windows mingw-x64
- iOS arm64 / simulator-arm64 (Swift export + XCFramework)
- JS (browser + Node.js)
- Wasm-JS (browser + Node.js)
- Android (API 24+)

### Porting guidelines

See [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) for translator discipline, port-lint header convention, and Rust → Kotlin idiom mapping.

### License

This Kotlin port is distributed under the same Apache-2.0 license as the upstream [`smol-rs/async-channel`](https://github.com/smol-rs/async-channel). See [LICENSE](LICENSE) (and any sibling `LICENSE-*` / `NOTICE` files mirrored from upstream) for the full text.

Original work copyrighted by the async-channel authors.  
Kotlin port: Copyright (c) 2026 Sydney Renee and The Solace Project.

### Acknowledgments

Thanks to the [`smol-rs/async-channel`](https://github.com/smol-rs/async-channel) maintainers and contributors for the original Rust implementation. This port reproduces their work in Kotlin Multiplatform; bug reports about upstream design or behavior should go to the upstream repository.
