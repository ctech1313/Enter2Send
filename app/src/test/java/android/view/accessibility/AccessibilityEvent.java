package android.view.accessibility;

public class AccessibilityEvent {
    private CharSequence packageName;

    public AccessibilityEvent() {}

    public AccessibilityEvent(CharSequence packageName) {
        this.packageName = packageName;
    }

    public CharSequence getPackageName() {
        return packageName;
    }

    public void setPackageName(CharSequence packageName) {
        this.packageName = packageName;
    }
}
