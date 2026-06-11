package dev.mewcode.agent.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表示单一层级的规则集合。
 */
public final class RuleSet {
    private final List<Rule> allowRules;
    private final List<Rule> denyRules;

    public RuleSet(List<Rule> allowRules, List<Rule> denyRules) {
        this.allowRules = Collections.unmodifiableList(new ArrayList<Rule>(allowRules));
        this.denyRules = Collections.unmodifiableList(new ArrayList<Rule>(denyRules));
    }

    public static RuleSet empty() {
        return new RuleSet(Collections.<Rule>emptyList(), Collections.<Rule>emptyList());
    }

    /**
     * 按同层 deny 优先于 allow 的规则匹配目标。
     */
    public Decision match(String friendlyTool, String target) {
        boolean filePattern = !"Bash".equalsIgnoreCase(friendlyTool);
        for (Rule rule : denyRules) {
            if (friendlyTool.equalsIgnoreCase(rule.tool()) && rule.matches(target, filePattern)) {
                return Decision.DENY;
            }
        }
        for (Rule rule : allowRules) {
            if (friendlyTool.equalsIgnoreCase(rule.tool()) && rule.matches(target, filePattern)) {
                return Decision.ALLOW;
            }
        }
        return null;
    }

    /**
     * 追加一条 allow 规则并返回新集合。
     */
    public RuleSet withAllow(Rule rule) {
        List<Rule> merged = new ArrayList<Rule>(allowRules);
        for (Rule existing : merged) {
            if (existing.tool().equalsIgnoreCase(rule.tool())
                    && existing.pattern().equals(rule.pattern())
                    && existing.allow() == rule.allow()) {
                return this;
            }
        }
        merged.add(rule);
        return new RuleSet(merged, denyRules);
    }

    public List<Rule> allowRules() {
        return allowRules;
    }

    public List<Rule> denyRules() {
        return denyRules;
    }
}
