package dev.wiles.myagent.plan;

/**
 * 一次计划执行的收支表。
 *
 * <p>三个数字加起来一定等于图里的任务总数——每个任务最后都得有个交代，
 * 要么成、要么败、要么被跳过。对不上就说明调度漏了谁。
 *
 * <p>{@code status} 和这三个数字是同一件事的两种说法：前者给界面用（一个词说清这轮怎么样），
 * 后者给报告用（具体成败几何）。由 {@link PlanExecutor} 保证两者一致，
 * 界面不必自己去数任务再下结论。
 */
public record PlanResult(PlanStatus status, int succeeded, int failed, int skipped) {

    public int total() {
        return succeeded + failed + skipped;
    }

    public boolean allSucceeded() {
        return status == PlanStatus.COMPLETED;
    }

    public boolean cancelled() {
        return status == PlanStatus.CANCELLED;
    }

    @Override
    public String toString() {
        return "成功 " + succeeded + " / 失败 " + failed + " / 跳过 " + skipped
                + (cancelled() ? "（已取消）" : "");
    }
}
