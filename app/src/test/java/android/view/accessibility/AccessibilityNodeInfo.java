package android.view.accessibility;

import java.util.ArrayList;
import java.util.List;

/** Mutable accessibility tree node for executing the production traversal code. */
public class AccessibilityNodeInfo {
    public static final int ACTION_FOCUS = 1;
    public static final int ACTION_CLICK = 16;
    public static final int ACTION_LONG_CLICK = 32;
    public static final int FOCUS_INPUT = 1;

    private static long nextSourceNodeId = 1L;
    private long sourceNodeId = nextSourceNodeId++;
    private CharSequence packageName = "com.openai.chatgpt";
    private int windowId = 1;
    private CharSequence className = "android.view.View";
    private CharSequence contentDescription;
    private String viewIdResourceName;
    private CharSequence text;
    private boolean textReadsAllowed;
    private int textReadCount;
    private boolean visibleToUser = true;
    private boolean enabled = true;
    private boolean editable;
    private boolean focused;
    private boolean clickable;
    private AccessibilityNodeInfo parent;
    private final List<AccessibilityNodeInfo> children = new ArrayList<>();
    private final List<AccessibilityAction> actionList = new ArrayList<>();
    private final List<Integer> performedActions = new ArrayList<>();
    private boolean actionResult = true;

    public CharSequence getPackageName() { return packageName; }
    public void setPackageName(CharSequence value) { packageName = value; }
    public int getWindowId() { return windowId; }
    public void setWindowId(int value) { windowId = value; }
    public CharSequence getClassName() { return className; }
    public void setClassName(CharSequence value) { className = value; }
    public CharSequence getContentDescription() { return contentDescription; }
    public void setContentDescription(CharSequence value) { contentDescription = value; }
    public String getViewIdResourceName() { return viewIdResourceName; }
    public void setViewIdResourceName(String value) { viewIdResourceName = value; }
    public CharSequence getText() {
        textReadCount++;
        if (!textReadsAllowed) {
            throw new AssertionError("AccessibilityNodeInfo.getText() violates the privacy contract");
        }
        return text;
    }
    public void setText(CharSequence value) { text = value; }
    public boolean isVisibleToUser() { return visibleToUser; }
    public void setVisibleToUser(boolean value) { visibleToUser = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isEditable() { return editable; }
    public void setEditable(boolean value) { editable = value; }
    public boolean isFocused() { return focused; }
    public void setFocused(boolean value) { focused = value; }
    public boolean isClickable() { return clickable; }
    public void setClickable(boolean value) { clickable = value; }
    public AccessibilityNodeInfo getParent() { return parent; }
    public int getChildCount() { return children.size(); }
    public AccessibilityNodeInfo getChild(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }
    public List<AccessibilityAction> getActionList() { return actionList; }

    public AccessibilityNodeInfo findFocus(int focus) {
        if (focused) return this;
        for (AccessibilityNodeInfo child : children) {
            AccessibilityNodeInfo match = child.findFocus(focus);
            if (match != null) return match;
        }
        return null;
    }

    public boolean performAction(int action) {
        performedActions.add(action);
        return actionResult;
    }

    public AccessibilityNodeInfo addChild(AccessibilityNodeInfo child) {
        children.add(child);
        child.parent = this;
        return this;
    }

    public List<AccessibilityNodeInfo> getChildrenForTest() { return children; }
    public List<Integer> getPerformedActionsForTest() { return performedActions; }
    public void setActionResultForTest(boolean value) { actionResult = value; }
    public void setSourceNodeIdForTest(long value) { sourceNodeId = value; }
    public long getSourceNodeIdForTest() { return sourceNodeId; }
    public void setTextReadsAllowedForTest(boolean value) { textReadsAllowed = value; }
    public int getTextReadCountForTest() { return textReadCount; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AccessibilityNodeInfo)) return false;
        AccessibilityNodeInfo node = (AccessibilityNodeInfo) other;
        return sourceNodeId == node.sourceNodeId && windowId == node.windowId;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(sourceNodeId) + windowId;
    }

    public static final class AccessibilityAction {
        private final int id;
        private final CharSequence label;

        public AccessibilityAction(int id, CharSequence label) {
            this.id = id;
            this.label = label;
        }

        public int getId() { return id; }
        public CharSequence getLabel() { return label; }
    }
}
