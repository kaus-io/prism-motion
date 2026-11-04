package com.zxhhyj.prism.motion.bench

/**
 * 基准入口：`--load` 只测加载成本，默认跑窗口基准。资产来自 demo-data。
 * Benchmark entry point: `--load` measures load cost only; the default runs the window bench.
 */
fun main(args: Array<String>) {
    Bench.run(args)
}
