package android.accessibilityservice;

import android.content.Context;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.Collections;
import java.util.List;

/** JVM fake with the Android methods used by the production service. */
public class AccessibilityService extends Context {
    private List<AccessibilityWindowInfo> windows = Collections.emptyList();
    private SparseArray<List<AccessibilityWindowInfo>> windowsOnAllDisplays = new SparseArray<>();

    public void onAccessibilityEvent(AccessibilityEvent event) {}

    public void onInterrupt() {}

    public void onDestroy() {}

    protected boolean onKeyEvent(KeyEvent event) {
        return false;
    }

    public List<AccessibilityWindowInfo> getWindows() {
        return windows;
    }

    public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
        return windowsOnAllDisplays;
    }

    public void setWindows(List<AccessibilityWindowInfo> windows) {
        this.windows = windows;
    }

    public void setWindowsOnAllDisplays(
            SparseArray<List<AccessibilityWindowInfo>> windowsOnAllDisplays) {
        this.windowsOnAllDisplays = windowsOnAllDisplays;
    }
}
