package dev.wiles.myagent.team;

import dev.wiles.myagent.snapshot.ShadowRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 返工保底：每轮验收完记下工作区和测试成绩，<b>交出去的永远不比最好的那一轮差</b>。
 *
 * <h3>为什么要这一层</h3>
 * 流水线原来的返工是「打回就整份重来，交最后一轮」。AsynCodeBench cookiecutter 上，
 * 验收连打回三轮，每轮五道工序全部重写，最后测试 133/202——比单 agent 一遍过的 150 还少。
 * 返工没有让它变好，只是让它变了；而系统交出去的恰好是改坏之后的那一版。
 *
 * <h3>怎么做</h3>
 * 成绩来自验收报的那一行 {@code 测试: 通过 X / 共 Y}（它本来就要跑测试，见 team/reviewer.md）。
 * 每轮拍一张影子仓库快照（只产 tree 对象，不碰工作区自己的 .git，见 {@link ShadowRepo}）：
 * 比之前最好的还少 → 立刻退回最好那一轮，返工到此为止；打平不退。
 * 没有「收场时再退一次」：退步当场就处理了，而最后一轮如果没报成绩，
 * 拿旧快照去比只会把那一轮的活误伤掉。
 * 影子仓库开在临时目录里，跟 /rewind 的检查点互不相干，用完就删。
 *
 * <h3>拿不到成绩就不管</h3>
 * 没有工作区、机器上没 git、验收没报成绩——任何一样缺了，这一层什么都不做，
 * 行为和以前一字不差。保底是锦上添花，不能因为它自己出岔子把流水线带倒。
 */
final class RoundGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RoundGuard.class);

    private static final RoundGuard NONE = new RoundGuard(null, null);

    private final ShadowRepo repo;
    private final Path dir;

    private int bestPassed = -1;
    private int bestRound;
    private String bestTree;

    private RoundGuard(ShadowRepo repo, Path dir) {
        this.repo = repo;
        this.dir = dir;
    }

    /** 开一个；没有工作区或开不起来时返回一个什么都不做的。 */
    static RoundGuard open(Path workspace) {
        if (workspace == null) {
            return NONE;
        }
        try {
            Path dir = Files.createTempDirectory("myagent-team-guard");
            ShadowRepo repo = ShadowRepo.open(workspace, dir);
            return repo == null ? NONE : new RoundGuard(repo, dir);
        } catch (IOException | RuntimeException e) {
            log.warn("返工保底开不起来，这一场不保底: {}", e.getMessage());
            return NONE;
        }
    }

    /**
     * 记下这一轮。
     *
     * @param score 验收报的 {通过, 总数}；null 表示没报，这一轮不参与比较
     * @return 这一轮比之前最好的少——工作区<b>已经</b>退回最好那一轮，调用方该收手了
     */
    boolean regressed(int round, int[] score) {
        if (repo == null || score == null) {
            return false;
        }
        try {
            String tree = repo.writeTree();
            int passed = score[0];
            if (passed > bestPassed) {
                bestPassed = passed;
                bestRound = round;
                bestTree = tree;
                return false;
            }
            if (passed < bestPassed) {
                log.info("第 {} 轮测试通过 {}，少于第 {} 轮的 {}：工作区退回第 {} 轮",
                        round, passed, bestRound, bestPassed, bestRound);
                repo.restore(bestTree);
                return true;
            }
            return false;       // 打平：不退，也不算更好
        } catch (IOException | RuntimeException e) {
            log.warn("第 {} 轮的保底快照没拍成，这一轮不参与比较: {}", round, e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            log.debug("保底的临时影子仓库没删掉: {}", e.getMessage());
        }
    }
}
