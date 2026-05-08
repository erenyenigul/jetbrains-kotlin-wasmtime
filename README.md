# Kotlin WASI w/ Wasmtime 

Run one the following commands for interactive execution:

`./gradlew wasmWasiWasmtimeProductionRun --no-daemon` 
or
`./gradlew wasmWasiWasmtimeDevelopmentRun --no-daemon`
  
Or you can simply pipe an `echo` command:

`echo 'hello' | ./gradlew wasmWasiWasmtimeProductionRun`
or
`echo 'hello' | ./gradlew wasmWasiWasmtimeDevelopmentRun`

### Acknowledgement

This project is built on top of [Kotlin WASI Template](https://github.com/Kotlin/kotlin-wasm-wasi-template).