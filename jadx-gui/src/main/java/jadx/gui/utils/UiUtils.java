package jadx.gui.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.TestOnly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import jadx.commons.app.JadxCommonEnv;
import jadx.commons.app.JadxSystemInfo;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.utils.StringUtils;
import jadx.core.utils.Utils;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.gui.jobs.ITaskProgress;
import jadx.gui.ui.codearea.AbstractCodeArea;

public class UiUtils {
	private static final Logger LOG = LoggerFactory.getLogger(UiUtils.class);

	public static final boolean JADX_GUI_DEBUG = JadxCommonEnv.getBool("JADX_GUI_DEBUG", false);

	/**
	 * The minimum about of memory in bytes we are trying to keep free, otherwise the application may
	 * run out of heap
	 * which ends up in a Java garbage collector running "amok" (CPU utilization 100% for each core and
	 * the UI is
	 * not responsive).
	 * <p>
	 * We can calculate and store this value here as the maximum heap is fixed for each JVM instance
	 * and can't be changed at runtime.
	 */
	public static final long MIN_FREE_MEMORY = calculateMinFreeMemory();

	public static final Runnable EMPTY_RUNNABLE = new Runnable() {
		@Override
		public void run() {
		}

		@Override
		public String toString() {
			return "EMPTY_RUNNABLE";
		}
	};

	private static final ExecutorService BACKGROUND_THREAD = Executors.newSingleThreadExecutor(Utils.simpleThreadFactory("utils-bg"));

	private UiUtils() {
	}

	public static FlatSVGIcon openSvgIcon(String name) {
		String iconPath = "icons/" + name + ".svg";
		FlatSVGIcon icon = new FlatSVGIcon(iconPath);
		boolean found;
		try {
			found = icon.hasFound();
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to load icon: " + iconPath, e);
		}
		if (!found) {
			throw new JadxRuntimeException("Icon not found: " + iconPath);
		}
		return icon;
	}

	public static ImageIcon openIcon(String name) {
		String iconPath = "/icons-16/" + name + ".png";
		URL resource = UiUtils.class.getResource(iconPath);
		if (resource == null) {
			throw new JadxRuntimeException("Icon not found: " + iconPath);
		}
		return new ImageIcon(resource);
	}

	public static Image openImage(String path) {
		URL resource = UiUtils.class.getResource(path);
		if (resource == null) {
			throw new JadxRuntimeException("Image not found: " + path);
		}
		return Toolkit.getDefaultToolkit().createImage(resource);
	}

	public static void addKeyBinding(JComponent comp, KeyStroke key, String id, Runnable action) {
		addKeyBinding(comp, key, id, new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	public static void addKeyBinding(JComponent comp, KeyStroke key, String id, Action action) {
		comp.getInputMap().put(key, id);
		comp.getActionMap().put(id, action);
	}

	public static void removeKeyBinding(JComponent comp, KeyStroke key, String id) {
		comp.getInputMap().remove(key);
		comp.getActionMap().remove(id);
	}

	public static String typeFormat(String name, ArgType type) {
		return name + " " + typeStr(type);
	}

	public static String typeFormatHtml(String name, ArgType type) {
		return wrapHtml(escapeHtml(name) + ' ' + fadeHtml(escapeHtml(typeStr(type))));
	}

	public static String fadeHtml(String htmlStr) {
		return "<span style='color:#888888;'>" + htmlStr + "</span>"; // TODO: get color from theme
	}

	public static String wrapHtml(String htmlStr) {
		return "<html><body><nobr>" + htmlStr + "</nobr></body></html>";
	}

	public static String escapeHtml(String str) {
		return str.replace("<", "&lt;").replace(">", "&gt;");
	}

	public static String typeStr(ArgType type) {
		if (type == null) {
			return "null";
		}
		if (type.isObject()) {
			if (type.isGenericType()) {
				return type.getObject();
			}
			ArgType wt = type.getWildcardType();
			if (wt != null) {
				ArgType.WildcardBound bound = type.getWildcardBound();
				if (bound == ArgType.WildcardBound.UNBOUND) {
					return bound.getStr();
				}
				return bound.getStr() + typeStr(wt);
			}
			String objName = objectShortName(type.getObject());
			ArgType outerType = type.getOuterType();
			if (outerType != null) {
				return typeStr(outerType) + '.' + objName;
			}
			List<ArgType> genericTypes = type.getGenericTypes();
			if (genericTypes != null) {
				String generics = Utils.listToString(genericTypes, ", ", UiUtils::typeStr);
				return objName + '<' + generics + '>';
			}
			return objName;
		}
		if (type.isArray()) {
			return typeStr(type.getArrayElement()) + "[]";
		}
		return type.toString();
	}

	private static String objectShortName(String obj) {
		int dot = obj.lastIndexOf('.');
		if (dot != -1) {
			return obj.substring(dot + 1);
		}
		return obj;
	}

	public static OverlayIcon makeIcon(AccessInfo af, Icon pub, Icon pri, Icon pro, Icon def) {
		Icon icon;
		if (af.isPublic()) {
			icon = pub;
		} else if (af.isPrivate()) {
			icon = pri;
		} else if (af.isProtected()) {
			icon = pro;
		} else {
			icon = def;
		}
		OverlayIcon overIcon = new OverlayIcon(icon);
		if (af.isFinal()) {
			overIcon.add(Icons.FINAL);
		}
		if (af.isStatic()) {
			overIcon.add(Icons.STATIC);
		}
		return overIcon;
	}

	/**
	 * @return 20% of the maximum heap size limited to 512 MB (bytes)
	 */
	public static long calculateMinFreeMemory() {
		Runtime runtime = Runtime.getRuntime();
		long minFree = (long) (runtime.maxMemory() * 0.2);
		return Math.min(minFree, 512 * 1024L * 1024L);
	}

	public static boolean isFreeMemoryAvailable() {
		Runtime runtime = Runtime.getRuntime();
		long maxMemory = runtime.maxMemory();
		long totalFree = runtime.freeMemory() + (maxMemory - runtime.totalMemory());
		return totalFree > MIN_FREE_MEMORY;
	}

	public static String memoryInfo() {
		Runtime runtime = Runtime.getRuntime();
		long maxMemory = runtime.maxMemory();
		long allocatedMemory = runtime.totalMemory();
		long freeMemory = runtime.freeMemory();

		return "heap: " + format(allocatedMemory - freeMemory)
				+ ", allocated: " + format(allocatedMemory)
				+ ", free: " + format(freeMemory)
				+ ", total free: " + format(freeMemory + maxMemory - allocatedMemory)
				+ ", max: " + format(maxMemory);
	}

	private static String format(long mem) {
		return (long) (mem / (double) (1024L * 1024L)) + "MB";
	}

	public static void setClipboardString(String text) {
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			Transferable transferable = new StringSelection(text);
			clipboard.setContents(transferable, null);
			LOG.debug("String '{}' copied to clipboard", text);
		} catch (Exception e) {
			LOG.error("Failed copy string '{}' to clipboard", text, e);
		}
	}

	public static void setWindowIcons(Window window) {
		List<Image> icons = new ArrayList<>();
		icons.add(UiUtils.openImage("/logos/jadx-logo-16px.png"));
		icons.add(UiUtils.openImage("/logos/jadx-logo-32px.png"));
		icons.add(UiUtils.openImage("/logos/jadx-logo-48px.png"));
		icons.add(UiUtils.openImage("/logos/jadx-logo.png"));
		window.setIconImages(icons);
	}

	public static final int CTRL_BNT_KEY = getCtrlButton();

	@SuppressWarnings("deprecation")
	@MagicConstant(flagsFromClass = InputEvent.class)
	private static int getCtrlButton() {
		if (JadxSystemInfo.IS_MAC) {
			return Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
		} else {
			return InputEvent.CTRL_DOWN_MASK;
		}
	}

	@MagicConstant(flagsFromClass = InputEvent.class)
	public static int ctrlButton() {
		return CTRL_BNT_KEY;
	}

	public static boolean isCtrlDown(KeyEvent keyEvent) {
		return keyEvent.getModifiersEx() == CTRL_BNT_KEY;
	}

	public static <T extends Window & RootPaneContainer> void addEscapeShortCutToDispose(T window) {
		KeyStroke stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
		window.getRootPane().registerKeyboardAction(e -> window.dispose(), stroke, JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	/**
	 * Get closest offset at mouse position
	 *
	 * @return -1 on error
	 */
	@SuppressWarnings("deprecation")
	public static int getOffsetAtMousePosition(AbstractCodeArea codeArea) {
		try {
			Point mousePos = getMousePosition(codeArea);
			return codeArea.viewToModel(mousePos);
		} catch (Exception e) {
			LOG.error("Failed to get offset at mouse position", e);
			return -1;
		}
	}

	public static Point getMousePosition(Component comp) {
		Point pos = MouseInfo.getPointerInfo().getLocation();
		SwingUtilities.convertPointFromScreen(pos, comp);
		return pos;
	}

	public static TreeNode getTreeNodeUnderMouse(JTree tree, MouseEvent mouseEvent) {
		TreePath path = tree.getClosestPathForLocation(mouseEvent.getX(), mouseEvent.getY());
		if (path == null) {
			return null;
		}
		// allow 'closest' path only at the right of the item row
		Rectangle pathBounds = tree.getPathBounds(path);
		if (pathBounds != null) {
			int y = mouseEvent.getY();
			if (y < pathBounds.y || y > (pathBounds.y + pathBounds.height)) {
				return null;
			}
			if (mouseEvent.getX() < pathBounds.x) {
				// exclude expand/collapse events
				return null;
			}
		}
		Object obj = path.getLastPathComponent();
		if (obj instanceof TreeNode) {
			tree.setSelectionPath(path);
			return (TreeNode) obj;
		}
		return null;
	}

	public static void showMessageBox(Component parent, String msg) {
		JOptionPane.showMessageDialog(parent, msg);
	}

	public static void errorMessage(Component parent, String message) {
		LOG.error(message);
		JOptionPane.showMessageDialog(parent, message,
				NLS.str("message.errorTitle"), JOptionPane.ERROR_MESSAGE);
	}

	public static void copyToClipboard(String text) {
		if (StringUtils.isEmpty(text)) {
			return;
		}
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			StringSelection selection = new StringSelection(text);
			clipboard.setContents(selection, selection);
		} catch (Exception e) {
			LOG.error("Failed copy text to clipboard", e);
		}
	}

	/**
	 * Owner field in Clipboard class can store reference to CodeArea.
	 * This prevents from garbage collection whole jadx object tree and cause memory leak.
	 * Trying to lost ownership by new empty selection.
	 */
	public static void resetClipboardOwner() {
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemSelection();
			if (clipboard != null) {
				StringSelection selection = new StringSelection("");
				clipboard.setContents(selection, selection);
			}
		} catch (Exception e) {
			LOG.error("Failed to reset clipboard owner", e);
		}
	}

	public static int calcProgress(ITaskProgress taskProgress) {
		return calcProgress(taskProgress.progress(), taskProgress.total());
	}

	public static int calcProgress(long done, long total) {
		if (done > total) {
			LOG.debug("Task progress has invalid values: done={}, total={}", done, total);
			return 100;
		}
		return Math.round(done * 100 / (float) total);
	}

	public static void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			// ignore
		}
	}

	public static void uiRun(Runnable runnable) {
		SwingUtilities.invokeLater(runnable);
	}

	public static void uiRunAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (InterruptedException e) {
			LOG.warn("UI thread interrupted, runnable: {}", runnable, e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Run task in background thread.
	 * Uses single thread, so all tasks are ordered.
	 */
	public static void bgRun(Runnable runnable) {
		BACKGROUND_THREAD.submit(runnable);
	}

	public static void uiThreadGuard() {
		if (!SwingUtilities.isEventDispatchThread()) {
			LOG.warn("Expect UI thread, got: {}", Thread.currentThread(), new JadxRuntimeException());
		}
	}

	public static void notUiThreadGuard() {
		if (SwingUtilities.isEventDispatchThread()) {
			LOG.warn("Expect background thread, got: {}", Thread.currentThread(), new JadxRuntimeException());
		}
	}

	@TestOnly
	public static void debugTimer(int periodInSeconds, Runnable action) {
		if (!LOG.isDebugEnabled()) {
			return;
		}
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				action.run();
			}
		}, 0, periodInSeconds * 1000L);
	}

	@TestOnly
	public static void printStackTrace(String label) {
		LOG.debug("StackTrace: {}", label, new Exception(label));
	}

	public static boolean isDarkTheme(Color background) {
		double brightness = (background.getRed() * 0.299
				+ background.getGreen() * 0.587
				+ background.getBlue() * 0.114) / 255;
		return brightness < 0.5;
	}

	/**
	 * Adjusts the brightness of a given {@code Color} object without altering its hue or saturation.
	 *
	 * <p>
	 * This method converts the input RGB color to the HSB (Hue, Saturation, Brightness) color model,
	 * multiplies its brightness component by the specified {@code factor}, and then converts it back
	 * to a new RGB {@code Color} object.
	 * </p>
	 *
	 * <p>
	 * The new brightness value is capped at {@code 1.0f} (maximum HSB brightness) to prevent
	 * colors from becoming invalid or exceeding full brightness.
	 * </p>
	 *
	 * How to use:
	 * <ul>
	 * <li>To make a color **brighter**: Use a {@code factor} greater than {@code 1.0f} (e.g.,
	 * {@code 1.2f}, {@code 1.5f}).</li>
	 * <li>To make a color **darker**: Use a {@code factor} less than {@code 1.0f} (e.g., {@code 0.8f},
	 * {@code 0.5f}).</li>
	 * <li>To keep the brightness **unchanged**: Use a {@code factor} of {@code 1.0f}.</li>
	 * </ul>
	 *
	 * <pre>{@code
	 * // Example usage:
	 * Color originalColor = Color.BLUE;
	 *
	 * // Make the blue color 50% brighter (factor 1.5)
	 * Color brighterBlue = adjustBrightness(originalColor, 1.5f);
	 *
	 * // Make the blue color 30% darker (factor 0.7)
	 * Color darkerBlue = adjustBrightness(originalColor, 0.7f);
	 *
	 * // Get the brightest possible version of the color (will cap at 1.0 brightness)
	 * Color maxBrightnessBlue = adjustBrightness(originalColor, 10.0f);
	 *
	 * // Get a very dark, almost black version
	 * Color veryDarkBlue = adjustBrightness(originalColor, 0.1f);
	 * }</pre>
	 *
	 * @param color  The original {@code Color} object whose brightness needs to be adjusted.
	 * @param factor The multiplier for the brightness.
	 * @return A new {@code Color} object with the adjusted brightness.
	 * @see java.awt.Color#RGBtoHSB(int, int, int, float[])
	 * @see java.awt.Color#getHSBColor(float, float, float)
	 */
	public static Color adjustBrightness(Color color, float factor) {
		float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
		hsb[2] = Math.min(1.0f, hsb[2] * factor); // Adjust brightness
		return Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
	}

	public static void highlightAsErrorField(final JTextField field, boolean isError) {
		if (isError) {
			field.putClientProperty("JComponent.outline", "error");
		} else {
			field.putClientProperty("JComponent.outline", "");
		}
		field.repaint();
	}
}
