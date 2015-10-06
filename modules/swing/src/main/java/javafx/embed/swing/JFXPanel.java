/*
 * Copyright (c) 2010, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package javafx.embed.swing;

import java.awt.AlphaComposite;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.Insets;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusListener;
import java.awt.im.InputMethodRequests;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.datatransfer.Clipboard;
import java.nio.IntBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.CountDownLatch;

import javafx.application.Platform;
import javafx.scene.Scene;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import com.sun.javafx.application.PlatformImpl;
import com.sun.javafx.cursor.CursorFrame;
import com.sun.javafx.embed.AbstractEvents;
import com.sun.javafx.embed.EmbeddedSceneInterface;
import com.sun.javafx.embed.EmbeddedStageInterface;
import com.sun.javafx.embed.HostInterface;
import com.sun.javafx.stage.EmbeddedWindow;
import com.sun.javafx.tk.Toolkit;
import com.sun.javafx.PlatformUtil;
import java.awt.event.InvocationEvent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import sun.awt.AppContext;
import sun.awt.CausedFocusEvent;
import sun.awt.SunToolkit;
import sun.java2d.SunGraphics2D;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

/**
* {@code JFXPanel} is a component to embed JavaFX content into
 * Swing applications. The content to be displayed is specified
 * with the {@link #setScene} method that accepts an instance of
 * JavaFX {@code Scene}. After the scene is assigned, it gets
 * repainted automatically. All the input and focus events are
 * forwarded to the scene transparently to the developer.
 * <p>
 * There are some restrictions related to {@code JFXPanel}. As a
 * Swing component, it should only be accessed from the event
 * dispatch thread, except the {@link #setScene} method, which can
 * be called either on the event dispatch thread or on the JavaFX
 * application thread.
 * <p>
 * Here is a typical pattern how {@code JFXPanel} can used:
 * <pre>
 *     public class Test {
 *
 *         private static void initAndShowGUI() {
 *             // This method is invoked on Swing thread
 *             JFrame frame = new JFrame("FX");
 *             final JFXPanel fxPanel = new JFXPanel();
 *             frame.add(fxPanel);
 *             frame.setVisible(true);
 *
 *             Platform.runLater(new Runnable() {
 *                 &#064;Override
 *                 public void run() {
 *                     initFX(fxPanel);
 *                 }
 *             });
 *         }
 *
 *         private static void initFX(JFXPanel fxPanel) {
 *             // This method is invoked on JavaFX thread
 *             Scene scene = createScene();
 *             fxPanel.setScene(scene);
 *         }
 *
 *         public static void main(String[] args) {
 *             SwingUtilities.invokeLater(new Runnable() {
 *                 &#064;Override
 *                 public void run() {
 *                     initAndShowGUI();
 *                 }
 *             });
 *         }
 *     }
 * </pre>
 *
 * @since JavaFX 2.0
 */
public class JFXPanel extends JComponent {

    private final static PlatformLogger log = PlatformLogger.getLogger(JFXPanel.class.getName());
    
    private static AtomicInteger instanceCount = new AtomicInteger(0);
    private static PlatformImpl.FinishListener finishListener;

    private HostContainer hostContainer;

    private volatile EmbeddedWindow stage;
    private volatile Scene scene;

    // Accessed on EDT only
    private SwingDnD dnd;

    private EmbeddedStageInterface stagePeer;
    private EmbeddedSceneInterface scenePeer;

    // The logical size of the FX content
    private int pWidth;
    private int pHeight;
    
    // The scale factor, used to translate b/w the logical (the FX content dimension)
    // and physical (the back buffer's dimension) coordinate spaces
    private int scaleFactor = 1;

    // Preferred size set from FX
    private volatile int pPreferredWidth = -1;
    private volatile int pPreferredHeight = -1;    

    // Cached copy of this component's location on screen to avoid
    // calling getLocationOnScreen() under the tree lock on FX thread
    private volatile int screenX = 0;
    private volatile int screenY = 0;

    // Accessed on EDT only
    private BufferedImage pixelsIm;

    private volatile float opacity = 1.0f;

    // Indicates how many times setFxEnabled(false) has been called.
    // A value of 0 means the component is enabled.
    private AtomicInteger disableCount = new AtomicInteger(0);

    private boolean isCapturingMouse = false;
            
    private synchronized void registerFinishListener() {
        if (instanceCount.getAndIncrement() > 0) {
            // Already registered
            return;
        }
        // Need to install a finish listener to catch calls to Platform.exit
        finishListener = new PlatformImpl.FinishListener() {
            @Override public void idle(boolean implicitExit) {
            }
            @Override public void exitCalled() {
            }
        };
        PlatformImpl.addListener(finishListener);
    }

    private synchronized void deregisterFinishListener() {
        if (instanceCount.decrementAndGet() > 0) {
            // Other JFXPanels still alive
            return;
        }
        PlatformImpl.removeListener(finishListener);
        finishListener = null;
    }

    // Initialize FX runtime when the JFXPanel instance is constructed
    private synchronized static void initFx() {
        // Note that calling PlatformImpl.startup more than once is OK
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            System.setProperty("glass.win.uiScale", "100%");
            System.setProperty("glass.win.renderScale", "100%");
            return null;
        });
        PlatformImpl.startup(() -> {
            // No need to do anything here
        });
    }

    /**
     * Creates a new {@code JFXPanel} object.
     * <p>
     * <b>Implementation note</b>: when the first {@code JFXPanel} object
     * is created, it implicitly initializes the JavaFX runtime. This is the
     * preferred way to initialize JavaFX in Swing.
     */
    public JFXPanel() {
        super();

        initFx();

        hostContainer = new HostContainer();

        enableEvents(InputEvent.COMPONENT_EVENT_MASK |
                     InputEvent.FOCUS_EVENT_MASK |
                     InputEvent.HIERARCHY_BOUNDS_EVENT_MASK |
                     InputEvent.HIERARCHY_EVENT_MASK |
                     InputEvent.MOUSE_EVENT_MASK |
                     InputEvent.MOUSE_MOTION_EVENT_MASK |
                     InputEvent.MOUSE_WHEEL_EVENT_MASK |
                     InputEvent.KEY_EVENT_MASK |
                     InputEvent.INPUT_METHOD_EVENT_MASK);

        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
    }

    /**
     * Returns the JavaFX scene attached to this {@code JFXPanel}.
     *
     * @return the {@code Scene} attached to this {@code JFXPanel}
     */
    public Scene getScene() {
        return scene;
    }

    /**
     * Attaches a {@code Scene} object to display in this {@code
     * JFXPanel}. This method can be called either on the event
     * dispatch thread or the JavaFX application thread.
     *
     * @param newScene a scene to display in this {@code JFXpanel}
     *
     * @see java.awt.EventQueue#isDispatchThread()
     * @see javafx.application.Platform#isFxApplicationThread()
     */
    public void setScene(final Scene newScene) {
        if (Toolkit.getToolkit().isFxUserThread()) {
            setSceneImpl(newScene);
        } else {
            final CountDownLatch initLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                setSceneImpl(newScene);
                initLatch.countDown();
            });
            try {
                initLatch.await();
            } catch (InterruptedException z) {
                z.printStackTrace(System.err);
            }
        }
    }

    /*
     * Called on JavaFX app thread.
     */
    private void setSceneImpl(Scene newScene) {
        if ((stage != null) && (newScene == null)) {
            stage.hide();
            stage = null;
        }
        scene = newScene;
        if ((stage == null) && (newScene != null)) {
            stage = new EmbeddedWindow(hostContainer);
        }
        if (stage != null) {
            stage.setScene(newScene);
            if (!stage.isShowing()) {
                stage.show();
            }
        }
    }

    /**
     * {@code JFXPanel}'s opacity is controlled by the JavaFX content
     * which is displayed in this component, so this method overrides
     * {@link javax.swing.JComponent#setOpaque(boolean)} to only accept a
     * {@code false} value. If this method is called with a {@code true}
     * value, no action is performed.
     *
     * @param opaque must be {@code false}
     */
    @Override
    public final void setOpaque(boolean opaque) {
        // Don't let user control opacity
        if (!opaque) {
            super.setOpaque(opaque);
        }
    }

    /**
     * {@code JFXPanel}'s opacity is controlled by the JavaFX content
     * which is displayed in this component, so this method overrides
     * {@link javax.swing.JComponent#isOpaque()} to always return a
     * {@code false} value.
     *
     * @return a {@code false} value
     */
    @Override
    public final boolean isOpaque() {
        return false;
    }

    private void sendMouseEventToFX(MouseEvent e) {
        if (scenePeer == null || !isFxEnabled()) {
            return;
        }
        
        // FX only supports 3 buttons so don't send the event for other buttons
        switch (e.getID()) {
            case MouseEvent.MOUSE_DRAGGED:
            case MouseEvent.MOUSE_PRESSED:
            case MouseEvent.MOUSE_RELEASED:
                if (e.getButton() > 3)  return;
                break;
        }

        int extModifiers = e.getModifiersEx();
        // Fix for RT-15457: we should report no mouse button upon mouse release, so
        // *BtnDown values are calculated based on extMofifiers, not e.getButton()
        boolean primaryBtnDown = (extModifiers & MouseEvent.BUTTON1_DOWN_MASK) != 0;
        boolean middleBtnDown = (extModifiers & MouseEvent.BUTTON2_DOWN_MASK) != 0;
        boolean secondaryBtnDown = (extModifiers & MouseEvent.BUTTON3_DOWN_MASK) != 0;
        // Fix for RT-16558: if a PRESSED event is consumed, e.g. by a Swing Popup,
        // subsequent DRAGGED and RELEASED events should not be sent to FX as well
        if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
            if (!isCapturingMouse) {
                return;
            }
        } else if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            isCapturingMouse = true;
        } else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
            if (!isCapturingMouse) {
                return;
            }
            isCapturingMouse = primaryBtnDown || middleBtnDown || secondaryBtnDown;
        } else if (e.getID() == MouseEvent.MOUSE_CLICKED) {
            // Don't send click events to FX, as they are generated in Scene
            return;
        }
        // A workaround until JDK-8065131 is fixed.
        boolean popupTrigger = false;
        if (e.getID() == MouseEvent.MOUSE_PRESSED || e.getID() == MouseEvent.MOUSE_RELEASED) {
            popupTrigger = e.isPopupTrigger();
        }
        scenePeer.mouseEvent(
                SwingEvents.mouseIDToEmbedMouseType(e.getID()),
                SwingEvents.mouseButtonToEmbedMouseButton(e.getButton(), extModifiers),
                primaryBtnDown, middleBtnDown, secondaryBtnDown,
                e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(),
                (extModifiers & MouseEvent.SHIFT_DOWN_MASK) != 0,
                (extModifiers & MouseEvent.CTRL_DOWN_MASK) != 0,
                (extModifiers & MouseEvent.ALT_DOWN_MASK) != 0,
                (extModifiers & MouseEvent.META_DOWN_MASK) != 0,
                SwingEvents.getWheelRotation(e), popupTrigger);
        if (e.isPopupTrigger()) {
            scenePeer.menuEvent(e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), false);
        }
    }

    /**
     * Overrides the {@link java.awt.Component#processMouseEvent(MouseEvent)}
     * method to dispatch the mouse event to the JavaFX scene attached to this
     * {@code JFXPanel}.
     *
     * @param e the mouse event to dispatch to the JavaFX scene
     */
    @Override
    protected void processMouseEvent(MouseEvent e) {
        if ((e.getID() == MouseEvent.MOUSE_PRESSED) &&
            (e.getButton() == MouseEvent.BUTTON1)) {
            if (!hasFocus()) {
                requestFocus();
            }
        }

        sendMouseEventToFX(e);
        super.processMouseEvent(e);
    }

    /**
     * Overrides the {@link java.awt.Component#processMouseMotionEvent(MouseEvent)}
     * method to dispatch the mouse motion event to the JavaFX scene attached to
     * this {@code JFXPanel}.
     *
     * @param e the mouse motion event to dispatch to the JavaFX scene
     */
    @Override
    protected void processMouseMotionEvent(MouseEvent e) {
        sendMouseEventToFX(e);
        super.processMouseMotionEvent(e);
    }

    /**
     * Overrides the
     * {@link java.awt.Component#processMouseWheelEvent(MouseWheelEvent)}
     * method to dispatch the mouse wheel event to the JavaFX scene attached
     * to this {@code JFXPanel}.
     *
     * @param e the mouse wheel event to dispatch to the JavaFX scene
     */
    @Override
    protected void processMouseWheelEvent(MouseWheelEvent e) {
        sendMouseEventToFX(e);
        super.processMouseWheelEvent(e);
    }

    private void sendKeyEventToFX(final KeyEvent e) {
        if (scenePeer == null || !isFxEnabled()) {
            return;
        }
        
        char[] chars = (e.getKeyChar() == KeyEvent.CHAR_UNDEFINED)
                       ? new char[] {}
                       : new char[] { SwingEvents.keyCharToEmbedKeyChar(e.getKeyChar()) };

        scenePeer.keyEvent(
                SwingEvents.keyIDToEmbedKeyType(e.getID()),
                e.getKeyCode(), chars,
                SwingEvents.keyModifiersToEmbedKeyModifiers(e.getModifiersEx()));
    }

    /**
     * Overrides the {@link java.awt.Component#processKeyEvent(KeyEvent)}
     * method to dispatch the key event to the JavaFX scene attached to this
     * {@code JFXPanel}.
     *
     * @param e the key event to dispatch to the JavaFX scene
     */
    @Override
    protected void processKeyEvent(KeyEvent e) {
        sendKeyEventToFX(e);
        super.processKeyEvent(e);
    }

    private void sendResizeEventToFX() {
        if (stagePeer != null) {
            stagePeer.setSize(pWidth, pHeight);
        }
        if (scenePeer != null) {
            scenePeer.setSize(pWidth, pHeight);
        }
    }

    /**
     * Overrides the
     * {@link java.awt.Component#processComponentEvent(ComponentEvent)}
     * method to dispatch {@link java.awt.event.ComponentEvent#COMPONENT_RESIZED}
     * events to the JavaFX scene attached to this {@code JFXPanel}. The JavaFX
     * scene object is then resized to match the {@code JFXPanel} size.
     *
     * @param e the component event to dispatch to the JavaFX scene
     */
    @Override
    protected void processComponentEvent(ComponentEvent e) {
        switch (e.getID()) {
            case ComponentEvent.COMPONENT_RESIZED: {
                updateComponentSize();
                break;
            }
            case ComponentEvent.COMPONENT_MOVED: {
                if (updateScreenLocation()) {
                    sendMoveEventToFX();
                }
                break;
            }
            default: {
                break;
            }
        }
        super.processComponentEvent(e);
    }

    // called on EDT only
    private void updateComponentSize() {
        int oldWidth = pWidth;
        int oldHeight = pHeight;
        // It's quite possible to get negative values here, this is not
        // what JavaFX embedded scenes/stages are ready to
        pWidth = Math.max(0, getWidth());
        pHeight = Math.max(0, getHeight());
        if (getBorder() != null) {
            Insets i = getBorder().getBorderInsets(this);
            pWidth -= (i.left + i.right);
            pHeight -= (i.top + i.bottom);
        }        
        if (oldWidth != pWidth || oldHeight != pHeight) {
            resizePixelBuffer(scaleFactor);
            sendResizeEventToFX();
        }
    }

    // This methods should only be called on EDT
    private boolean updateScreenLocation() {
        synchronized (getTreeLock()) {
            if (isShowing()) {
                Point p = getLocationOnScreen();
                screenX = p.x;
                screenY = p.y;
                return true;
            }
        }
        return false;
    }

    private void sendMoveEventToFX() {
        if (stagePeer == null) {
            return;
        }

        stagePeer.setLocation(screenX, screenY);
    }

    /**
     * Overrides the
     * {@link java.awt.Component#processHierarchyBoundsEvent(HierarchyEvent)}
     * method to process {@link java.awt.event.HierarchyEvent#ANCESTOR_MOVED}
     * events and update the JavaFX scene location to match the {@code
     * JFXPanel} location on the screen.
     *
     * @param e the hierarchy bounds event to process
     */
    @Override
    protected void processHierarchyBoundsEvent(HierarchyEvent e) {
        if (e.getID() == HierarchyEvent.ANCESTOR_MOVED) {
            if (updateScreenLocation()) {
                sendMoveEventToFX();
            }
        }
        super.processHierarchyBoundsEvent(e);
    }

    @Override
    protected void processHierarchyEvent(HierarchyEvent e) {
        if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
            if (updateScreenLocation()) {
                sendMoveEventToFX();
            }
        }
        super.processHierarchyEvent(e);
    }

    private void sendFocusEventToFX(final FocusEvent e) {
        if ((stage == null) || (stagePeer == null) || !isFxEnabled()) {
            return;
        }

        boolean focused = (e.getID() == FocusEvent.FOCUS_GAINED);
        int focusCause = (focused ? AbstractEvents.FOCUSEVENT_ACTIVATED :
                                      AbstractEvents.FOCUSEVENT_DEACTIVATED);

        if (focused && (e instanceof CausedFocusEvent)) {
            CausedFocusEvent ce = (CausedFocusEvent)e;
            if (ce.getCause() == CausedFocusEvent.Cause.TRAVERSAL_FORWARD) {
                focusCause = AbstractEvents.FOCUSEVENT_TRAVERSED_FORWARD;
                        } else if (ce.getCause() == sun.awt.CausedFocusEvent.Cause.TRAVERSAL_BACKWARD) {
                focusCause = AbstractEvents.FOCUSEVENT_TRAVERSED_BACKWARD;
                        }
                    }
        stagePeer.setFocused(focused, focusCause);
    }

    /**
     * Overrides the
     * {@link java.awt.Component#processFocusEvent(FocusEvent)}
     * method to dispatch focus events to the JavaFX scene attached to this
     * {@code JFXPanel}.
     *
     * @param e the focus event to dispatch to the JavaFX scene
     */
    @Override
    protected void processFocusEvent(FocusEvent e) {
        sendFocusEventToFX(e);
        super.processFocusEvent(e);
    }

    // called on EDT only
    private void resizePixelBuffer(int newScaleFactor) {
        if ((pWidth <= 0) || (pHeight <= 0)) {
             pixelsIm = null;
        } else {
            BufferedImage oldIm = pixelsIm;                        
            pixelsIm = new BufferedImage(pWidth * newScaleFactor,
                                         pHeight * newScaleFactor,
                                         BufferedImage.TYPE_INT_ARGB);
            if (oldIm != null) {
                double ratio = newScaleFactor / scaleFactor;
                // Transform old size to the new coordinate space.
                int oldW = (int)Math.round(oldIm.getWidth() * ratio);
                int oldH = (int)Math.round(oldIm.getHeight() * ratio);
                 
                Graphics g = pixelsIm.getGraphics();
                try {
                    g.drawImage(oldIm, 0, 0, oldW, oldH, null);
                } finally {
                    g.dispose();
                }
            }
        }
    }
    
    @Override
    protected void processInputMethodEvent(InputMethodEvent e) {
        if (e.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) {
            sendInputMethodEventToFX(e);
        }
        super.processInputMethodEvent(e);
    }

    private void sendInputMethodEventToFX(InputMethodEvent e) {
        String t = InputMethodSupport.getTextForEvent(e);

        scenePeer.inputMethodEvent(
                javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                InputMethodSupport.inputMethodEventComposed(t, e.getCommittedCharacterCount()),
                t.substring(0, e.getCommittedCharacterCount()),
                e.getCaret().getInsertionIndex());
    }


    /**
     * Overrides the {@link javax.swing.JComponent#paintComponent(Graphics)}
     * method to paint the content of the JavaFX scene attached to this
     * {@code JFXpanel}.
     *
     * @param g the Graphics context in which to paint
     *
     * @see #isOpaque()
     */
    @Override
    protected void paintComponent(Graphics g) {
        if ((scenePeer == null) || (pixelsIm == null)) {
            return;
        }
        
        DataBufferInt dataBuf = (DataBufferInt)pixelsIm.getRaster().getDataBuffer();
        int[] pixelsData = dataBuf.getData();
        IntBuffer buf = IntBuffer.wrap(pixelsData);           
        if (!scenePeer.getPixels(buf, pWidth, pHeight)) {
            // In this case we just render what we have so far in the buffer.
        }

        Graphics gg = null;
        try {
            gg = g.create();
            if ((opacity < 1.0f) && (gg instanceof Graphics2D)) {
                Graphics2D g2d = (Graphics2D)gg;
                AlphaComposite c = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity);
                g2d.setComposite(c);
            }
            if (getBorder() != null) {
                Insets i = getBorder().getBorderInsets(this);
                gg.translate(i.left, i.top);
            }
            gg.drawImage(pixelsIm, 0, 0, pWidth, pHeight, null);

            int newScaleFactor = scaleFactor;
            if (g instanceof SunGraphics2D) {
                newScaleFactor = ((SunGraphics2D)g).surfaceData.getDefaultScale();
            }            
            if (scaleFactor != newScaleFactor) {
                resizePixelBuffer(newScaleFactor);
                // The scene will request repaint.
                scenePeer.setPixelScaleFactor(newScaleFactor);
                scaleFactor = newScaleFactor;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            if (gg != null) {
                gg.dispose();
            }
        }
    }

    /**
     * Returns the preferred size of this {@code JFXPanel}, either
     * previously set with {@link #setPreferredSize(Dimension)} or
     * based on the content of the JavaFX scene attached to this {@code
     * JFXPanel}.
     *
     * @return prefSize this {@code JFXPanel} preferred size
     */
    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet() || scenePeer == null) {
            return super.getPreferredSize();
        }
        return new Dimension(pPreferredWidth, pPreferredHeight);
    }

    private boolean isFxEnabled() {
        return this.disableCount.get() == 0;
    }

    private void setFxEnabled(boolean enabled) {
        if (!enabled) {
            if (disableCount.incrementAndGet() == 1) {
                if (dnd != null) {
                    dnd.removeNotify();
                }
            }
        } else {
            if (disableCount.get() == 0) {
                //should report a warning about an extra enable call ?
                return;
            }
            if (disableCount.decrementAndGet() == 0) {
                if (dnd != null) {
                    dnd.addNotify();
                }
            }
        }
    }

    private final AWTEventListener ungrabListener = event -> {
        if (event instanceof sun.awt.UngrabEvent) {
            SwingFXUtils.runOnFxThread(() -> {
                if (JFXPanel.this.stagePeer != null) {
                    JFXPanel.this.stagePeer.focusUngrab();
                }
            });
        }
        if (event instanceof MouseEvent) {
            // Synthesize FOCUS_UNGRAB if user clicks the AWT top-level window
            // that contains the JFXPanel.
            if (event.getID() == MouseEvent.MOUSE_PRESSED && event.getSource() instanceof Component) {
                final Window jfxPanelWindow = SwingUtilities.getWindowAncestor(JFXPanel.this);
                final Component source = (Component)event.getSource();
                final Window eventWindow = source instanceof Window ? (Window)source : SwingUtilities.getWindowAncestor(source);

                if (jfxPanelWindow == eventWindow) {
                    SwingFXUtils.runOnFxThread(() -> {
                        if (JFXPanel.this.stagePeer != null) {
                            // No need to check if grab is active or not.
                            // NoAutoHide popups don't request the grab and
                            // ignore the Ungrab event anyway.
                            // AutoHide popups actually should be hidden when
                            // user clicks some non-FX content, even if for
                            // some reason they didn't install the grab when
                            // they were shown.
                            JFXPanel.this.stagePeer.focusUngrab();
                        }
                    });
                }
            }
        }
    };

    /**
     * Notifies this component that it now has a parent component. When this
     * method is invoked, the chain of parent components is set up with
     * KeyboardAction event listeners.
     */
    @Override
    public void addNotify() {
        super.addNotify();

        registerFinishListener();

        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            JFXPanel.this.getToolkit().addAWTEventListener(ungrabListener,
                SunToolkit.GRAB_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
            return null;
        });
        updateComponentSize(); // see RT-23603
        SwingFXUtils.runOnFxThread(() -> {
            if ((stage != null) && !stage.isShowing()) {
                stage.show();
                sendMoveEventToFX();
            }
        });
    }

    @Override
    public InputMethodRequests getInputMethodRequests() {
        if (scenePeer == null) {
            return null;
        }
        return new InputMethodSupport.InputMethodRequestsAdapter(scenePeer.getInputMethodRequests());
    }

    /**
     * Notifies this component that it no longer has a parent component.
     * When this method is invoked, any KeyboardActions set up in the the
     * chain of parent components are removed.
     */
    @Override public void removeNotify() {
        SwingFXUtils.runOnFxThread(() -> {
            if ((stage != null) && stage.isShowing()) {
                stage.hide();
            }
        });

        pixelsIm = null;
        pWidth = 0;
        pHeight = 0;
        
        super.removeNotify();

        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            JFXPanel.this.getToolkit().removeAWTEventListener(ungrabListener);
            return null;
        });

        /* see CR 4867453 */
        getInputContext().removeNotify(this);

        deregisterFinishListener();
    }
    
    private void invokeOnClientEDT(Runnable r) {
        AppContext context = SunToolkit.targetToAppContext(this);
        if (context == null) {
            if (log.isLoggable(Level.FINE)) log.fine("null AppContext encountered!");
            return;
        }
        SunToolkit.postEvent(context, new InvocationEvent(this, r));
    }

    private class HostContainer implements HostInterface {

        @Override
        public void setEmbeddedStage(EmbeddedStageInterface embeddedStage) {
            stagePeer = embeddedStage;
            if (stagePeer == null) {
                return;
            }
            if (pWidth > 0 && pHeight > 0) {
                stagePeer.setSize(pWidth, pHeight);
            }
            invokeOnClientEDT(() -> {
                if (JFXPanel.this.isFocusOwner()) {
                    stagePeer.setFocused(true, AbstractEvents.FOCUSEVENT_ACTIVATED);
                }
            });
            sendMoveEventToFX();
        }

        @Override
        public void setEmbeddedScene(EmbeddedSceneInterface embeddedScene) {
            if (scenePeer == embeddedScene) {
                return;
            }
            scenePeer = embeddedScene;
            if (scenePeer == null) {
                invokeOnClientEDT(() -> {
                    if (dnd != null) {
                        dnd.removeNotify();
                        dnd = null;
                    }
                });
                return;
            }
            if (pWidth > 0 && pHeight > 0) {
                scenePeer.setSize(pWidth, pHeight);
            }
            scenePeer.setPixelScaleFactor(scaleFactor);
            
            invokeOnClientEDT(() -> {
                dnd = new SwingDnD(JFXPanel.this, scenePeer);
                dnd.addNotify();
                if (scenePeer != null) {
                    scenePeer.setDragStartListener(dnd.getDragStartListener());
                }
            });
        }

        @Override
        public boolean requestFocus() {
            return requestFocusInWindow();
        }

        @Override
        public boolean traverseFocusOut(boolean forward) {
            KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            if (forward) {
                kfm.focusNextComponent(JFXPanel.this);
            } else {
                kfm.focusPreviousComponent(JFXPanel.this);
            }
            return true;
        }

        @Override
        public void setPreferredSize(final int width, final int height) {
            invokeOnClientEDT(() -> {
                JFXPanel.this.pPreferredWidth = width;
                JFXPanel.this.pPreferredHeight = height;
                JFXPanel.this.revalidate();
            });
        }

        @Override
        public void repaint() {
            invokeOnClientEDT(() -> {
                JFXPanel.this.repaint();
            });
        }

        @Override
        public void setEnabled(final boolean enabled) {
            JFXPanel.this.setFxEnabled(enabled);
        }

        @Override
        public void setCursor(CursorFrame cursorFrame) {
            final Cursor cursor = getPlatformCursor(cursorFrame);
            invokeOnClientEDT(() -> {
                JFXPanel.this.setCursor(cursor);
            });
        }

        private Cursor getPlatformCursor(final CursorFrame cursorFrame) {
            final Cursor cachedPlatformCursor =
                    cursorFrame.getPlatformCursor(Cursor.class);
            if (cachedPlatformCursor != null) {
                // platform cursor already cached
                return cachedPlatformCursor;
            }

            // platform cursor not cached yet
            final Cursor platformCursor =
                    SwingCursors.embedCursorToCursor(cursorFrame);
            cursorFrame.setPlatforCursor(Cursor.class, platformCursor);

            return platformCursor;
        }

        @Override
        public boolean grabFocus() {
            // On X11 grab is limited to a single XDisplay connection,
            // so we can't delegate it to another GUI toolkit.
            if (PlatformUtil.isLinux()) return true;

            invokeOnClientEDT(() -> {
                Window window = SwingUtilities.getWindowAncestor(JFXPanel.this);
                if (window != null) {
                    if (JFXPanel.this.getToolkit() instanceof SunToolkit) {
                        ((SunToolkit)JFXPanel.this.getToolkit()).grab(window);
                    }
                }
            });

            return true; // Oh, well...
        }

        @Override
        public void ungrabFocus() {
            // On X11 grab is limited to a single XDisplay connection,
            // so we can't delegate it to another GUI toolkit.
            if (PlatformUtil.isLinux()) return;

            invokeOnClientEDT(() -> {
                Window window = SwingUtilities.getWindowAncestor(JFXPanel.this);
                if (window != null) {
                    if (JFXPanel.this.getToolkit() instanceof SunToolkit) {
                        ((SunToolkit)JFXPanel.this.getToolkit()).ungrab(window);
                    }
                }
            });
        }
    }
}