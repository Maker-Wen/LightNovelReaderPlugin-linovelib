package io.nightfish.lightnovelreader.plugin.linovelib.migration

/** Dialog copy stays independent of Android rendering so terminal instructions can be verified. */
internal object MigrationMessages {
    const val completed = "已迁入可恢复的数据，请重新打开阅读器"
    const val restartRequired = "暂时无法确认本次保存结果。为避免重复合并，请关闭并重新打开阅读器后再检查。"

    fun reason(reason: MigrationReason): String = when (reason) {
        MigrationReason.NoData -> "没有可迁入的 TW 数据，本次未保存合并结果，可继续使用阅读器。"
        MigrationReason.TargetAlreadyMigratedInactive -> "已检测到迁入记录，本次没有再次合并，可继续使用阅读器。"
        MigrationReason.NotReady -> "应用尚未准备好，本次未保存合并结果。可以稍后重试，或关闭提示继续使用阅读器。"
        MigrationReason.StartupConflict -> "当前有后台任务，本次未保存合并结果。可关闭提示继续使用阅读器。"
        MigrationReason.SessionChanged -> "当前启动会话已变化，本次未保存合并结果。可关闭提示继续使用阅读器。"
        MigrationReason.DisplayUnavailable -> "迁入窗口未能显示，本次未保存合并结果。可关闭提示继续使用阅读器。"
        MigrationReason.SchedulingFailure -> "迁入任务未能启动，本次未保存合并结果。可以重试，或关闭提示继续使用阅读器。"
        MigrationReason.SourceChanged -> "当前数据源已变化，本次未保存合并结果。可关闭提示继续使用阅读器。"
        MigrationReason.InputChanged -> "本地快照已变化，本次未保存合并结果。可以重试，或关闭提示继续使用阅读器。"
        MigrationReason.UntrustedCompletion -> "无法确认此前是否已迁入，为避免重复累计，本次已跳过迁入。可继续使用阅读器。"
        MigrationReason.InvalidData -> "没有可安全迁入的数据，本次未保存合并结果。可继续使用阅读器。"
        MigrationReason.UnsupportedHost -> "当前阅读器暂不支持本次迁入，本次未保存合并结果。可继续使用阅读器。"
        MigrationReason.StorageFailure -> "本地数据读取或保存失败，本次合并结果未保存。可以重试，或关闭提示继续使用阅读器。"
    }
}
