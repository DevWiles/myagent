package dev.wiles.myagent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 这台机器上、这个工作区里有哪些技能。
 *
 * <h3>为什么也读 {@code .claude/skills}</h3>
 * 技能是<b>用户自己写的东西</b>，不是某个产品的配置——格式还是同一份公开规范。
 * 只认 {@code .myagent/skills} 的话，用户手上现成的那些一份都用不上，
 * 而「标准格式」这四个字就只剩形式意义了。所以两处都读，
 * 启动日志里如实说清从哪儿读到了几份，不偷偷摸摸。
 *
 * <h3>撞名时谁赢</h3>
 * 工作区里的压过家目录里的，{@code .myagent} 压过 {@code .claude}。
 * 前一条是为了让一个仓库能给自己定制技能；后一条是为了让「专门给 myagent 写的」
 * 能盖住通用的那份。<b>先扫到的赢</b>，后面同名的连读都不读——
 * 读了再丢会白花一次 IO，更要紧的是日志里会出现两份同名技能，看着像 bug。
 */
public final class SkillLibrary {

    private static final Logger log = LoggerFactory.getLogger(SkillLibrary.class);

    /** 一台机器上最多接多少份。清单是每一轮的固定开销，无上限等于给自己开个口子。 */
    static final int MAX_SKILLS = 64;

    private final Map<String, Skill> byId;

    private SkillLibrary(Map<String, Skill> byId) {
        this.byId = byId;
    }

    public static SkillLibrary empty() {
        return new SkillLibrary(Map.of());
    }

    /** 按优先级从高到低列出要扫的目录。工作区可以为 null（没有工作区的场合）。 */
    static List<Path> searchPath(Path workspace, Path home) {
        List<Path> dirs = new ArrayList<>();
        if (workspace != null) {
            dirs.add(workspace.resolve(".myagent").resolve("skills"));
            dirs.add(workspace.resolve(".claude").resolve("skills"));
        }
        if (home != null) {
            dirs.add(home.resolve(".myagent").resolve("skills"));
            dirs.add(home.resolve(".claude").resolve("skills"));
        }
        return dirs;
    }

    public static SkillLibrary load(Path workspace) {
        return load(searchPath(workspace, Path.of(System.getProperty("user.home"))));
    }

    static SkillLibrary load(List<Path> dirs) {
        Map<String, Skill> found = new LinkedHashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            int before = found.size();
            for (Path candidate : childDirs(dir)) {
                if (found.size() >= MAX_SKILLS) {
                    log.warn("技能数量到上限 {} 了，{} 往后的不再读", MAX_SKILLS, dir);
                    break;
                }
                String id = candidate.getFileName().toString();
                if (found.containsKey(id)) {
                    continue;                // 优先级高的已经拿下这个名字，见类注释
                }
                Skill skill = Skill.read(candidate);
                if (skill != null) {
                    found.put(id, skill);
                }
            }
            if (found.size() > before) {
                log.info("从 {} 读到 {} 份技能", dir, found.size() - before);
            }
        }
        // 不能用 Map.copyOf——它<b>不保证迭代顺序</b>，扫描顺序在这一行就丢了。
        // 症状是每次启动清单顺序都不一样，服务端前缀缓存全程不命中，
        // 而功能完全正常，没人会往这儿想
        return new SkillLibrary(Collections.unmodifiableMap(new LinkedHashMap<>(found)));
    }

    /** 目录名排序，保证清单顺序稳定——顺序一抖，服务端的前缀缓存就白瞎了。 */
    private static List<Path> childDirs(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("列不动 {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** 模型可以自己选的那些，清单里列的就是它们。 */
    public List<Skill> invocable() {
        return byId.values().stream().filter(Skill::modelInvocable).toList();
    }

    public Optional<Skill> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id.strip()));
    }

    /** 全部，含不让模型自己选的。给界面和测试。 */
    public List<Skill> all() {
        return List.copyOf(byId.values());
    }
}
