package android.view.accessibility;

public class AccessibilityWindowInfo {
    private AccessibilityNodeInfo root;
    private int id = 1;
    private boolean focused = true;

    public AccessibilityNodeInfo getRoot() {
        return root;
    }

    public void setRoot(AccessibilityNodeInfo root) {
        this.root = root;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }
}
