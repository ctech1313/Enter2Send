package android.view;

public class KeyEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_NUMPAD_ENTER = 160;

    private final int action;
    private final int keyCode;
    private final int repeatCount;
    private final boolean shiftPressed;
    private final boolean ctrlPressed;

    public KeyEvent(int action, int keyCode) {
        this(action, keyCode, 0, false, false);
    }

    public KeyEvent(
            int action,
            int keyCode,
            int repeatCount,
            boolean shiftPressed,
            boolean ctrlPressed) {
        this.action = action;
        this.keyCode = keyCode;
        this.repeatCount = repeatCount;
        this.shiftPressed = shiftPressed;
        this.ctrlPressed = ctrlPressed;
    }

    public int getAction() {
        return action;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public boolean isShiftPressed() {
        return shiftPressed;
    }

    public boolean isCtrlPressed() {
        return ctrlPressed;
    }
}
