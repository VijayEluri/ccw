package ccw.repl;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.internal.editors.text.EditorsPlugin;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.ui.texteditor.StatusLineContributionItem;

import ccw.CCWPlugin;
import ccw.editors.clojure.ClojureDocumentProvider;
import ccw.editors.clojure.ClojureSourceViewer;
import ccw.editors.clojure.ClojureSourceViewerConfiguration;
import ccw.editors.clojure.IClojureEditor;
import ccw.editors.clojure.IClojureEditorActionDefinitionIds;
import ccw.preferences.PreferenceConstants;
import ccw.util.ClojureInvoker;
import ccw.util.DisplayUtil;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.Symbol;
import clojure.lang.Var;
import clojure.osgi.ClojureOSGi;
import clojure.tools.nrepl.Connection;
import clojure.tools.nrepl.Connection.Response;

public class REPLView extends ViewPart implements IAdaptable {

	private static final ClojureInvoker editorSupport = ClojureInvoker.newInvoker(
            CCWPlugin.getDefault(),
            "ccw.editors.clojure.editor-support");
    
	private static final ClojureInvoker str = ClojureInvoker.newInvoker(
            CCWPlugin.getDefault(),
            "clojure.string");

	/* Keep this in sync with the context defined in plugin.xml */
	public static final String CCW_UI_CONTEXT_REPL = "ccw.ui.context.repl";
	
    public static final String VIEW_ID = "ccw.view.repl";
    public static final AtomicReference<REPLView> activeREPL = new AtomicReference();

    private static Var log;
    private static Var configureREPLView;
    private static Var handleResponses;
    static {
        try {
            ClojureOSGi.require(CCWPlugin.getDefault().getBundle().getBundleContext(), "ccw.repl.view-helpers");
            log = Var.find(Symbol.intern("ccw.repl.view-helpers/log"));
            configureREPLView = Var.find(Symbol.intern("ccw.repl.view-helpers/configure-repl-view"));
            handleResponses = Var.find(Symbol.intern("ccw.repl.view-helpers/handle-responses"));
        } catch (Exception e) {
            CCWPlugin.logError("Could not initialize view helpers.", e);
        }
    }

    /**
     * This misery around secondary IDs is due to the fact that:
     * eclipse really, really wants views to be pre-defined; while you can have a view type that
     * has multiple instances, doing so requires having *stable* secondary IDs in order for the
     * IDE to retain positioning and size preferences.
     * We could just use UUIDs to identify different REPL views, but that means no position info
     * will be retained.  We could use nREPL URLs, but those are almost as bad as UUIDs insofar as
     * the autoselected ports are hardly ever used twice.
     * Try as I might, I cannot find a way to get a view to (a) be displayed in a particular place
     * in the IDE (short of defining a Clojure perspective!) or (b) be notified of when a REPL
     * view is moved to begin with.
     * 
     * This approach will result in a user "training" the IDE where to put REPL views
     * (with seemingly intransigent or arbitrary behaviour to start); eventually, REPLs will
     * all end up displaying in the desired location.
     */
    private static Set<String> SECONDARY_VIEW_IDS = new TreeSet<String>() {{
       // no one will create more than 1000 REPLs at a time, right? :-P
       for (int i = 0; i < 1000; i++) add(String.format("%03d", i)); 
    }};
    private static String getSecondaryId () {
        synchronized (SECONDARY_VIEW_IDS) {
            String id = SECONDARY_VIEW_IDS.iterator().next();
            SECONDARY_VIEW_IDS.remove(id);
            return id;
        }
    }
    private static void releaseSecondaryId (String id) {
        synchronized (SECONDARY_VIEW_IDS) {
            SECONDARY_VIEW_IDS.add(id);
        }
    }
    
    private static final Keyword inputExprLogType = Keyword.intern("in-expr");
    private static final Pattern boostIndent = Pattern.compile("^", Pattern.MULTILINE);
    
    // TODO would like to eliminate separate log view, but:
    // 1. PareditAutoEditStrategy gets all text from a source document, and bails badly if its not well-formed
    //      (and REPL output is guaranteed to not be well-formed)
    // 2. Even if (1) were fixed/changed, it's not clear to me how to "partition" an IDocument, or compose IDocuments
    //     so that we can have one range that is still *highlighted* for clojure content (and not editable),
    //     and another range that is editable and has full paredit, code completion, etc.
    StyledText logPanel;
    private ClojureSourceViewer viewer;
    public StyledText viewerWidget; // public only to simplify interop with helpers impl'd in Clojure
    private ClojureSourceViewerConfiguration viewerConfig;
    
    private final IPropertyChangeListener fontChangeListener = new IPropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent event) {
            if (event.getProperty().equals(JFaceResources.TEXT_FONT)) resetFont();
        }
    };
        
    private Connection interactive;
    private Connection toolConnection;
    
    private IConsole console;
    private ILaunch launch;
    
    private String currentNamespace = "user";
    private Map<String, Object> describeInfo;
    private IFn evalExpression;
    private String sessionId;
    private String secondaryId;
    
    /* function implementing load previous/next command from history into input area */
    private IFn historyActionFn;
    
    public void setHistoryActionFn(IFn historyActionFn) {
    	this.historyActionFn = historyActionFn;
    }
    public IFn getHistoryActionFn() {
    	return this.historyActionFn;
    }
    
    private SourceViewerDecorationSupport fSourceViewerDecorationSupport;
	private StatusLineContributionItem structuralEditionModeStatusContributionItem;
    
    public REPLView () {}    
    
    private void resetFont () {
        Font font= JFaceResources.getTextFont();
        logPanel.setFont(font);
        viewerWidget.setFont(font);
    }
    
    private void copyToLog (StyledText s) {
        // sadly, need to reset text on the ST in order to get formatting/style ranges...
        s.setText(boostIndent.matcher(s.getText()).replaceAll("   ").replaceFirst("^\\s+", "=> "));
        int start = logPanel.getCharCount();
        try {
            log.invoke(logPanel, s.getText(), inputExprLogType);
            for (StyleRange sr : s.getStyleRanges()) {
                sr.start += start;
                logPanel.setStyleRange(sr);
            }
        } catch (Exception e) {
            // should never happen
            CCWPlugin.logError("Could not copy expression to log", e);
        }
    }
    
    private String removeTrailingSpaces(String s) {
    	return (String) str._("trimr", s);
    }
    private void evalExpression () {
    	// We remove trailing spaces so that we do not embark extra spaces,
    	// newlines, etc. for example when evaluating after having hit the
    	// Enter key (which automatically adds a new line
    	viewerWidget.setText(removeTrailingSpaces(viewerWidget.getText()));
        evalExpression(viewerWidget.getText(), true, false);
        copyToLog(viewerWidget);
        viewerWidget.setText("");
    }
    
    public void evalExpression (String s) {
        evalExpression(s, true, true);
    }
    
    public void evalExpression (String s, boolean userInput, boolean logExpression) {
        try {
            if (s.trim().length() > 0) {
                if (logExpression) log.invoke(logPanel, s, inputExprLogType);
                evalExpression.invoke(s, userInput);
            }
        } catch (Exception e) {
            CCWPlugin.logError(e);
        }
    }

    public void printErrorDetail() {
        evalExpression("(binding [*out* *err*] (if-not *e (println \"No prior exception bound to *e.\") (clojure.repl/pst *e)))", false, false);
    }

    public void sendInterrupt() {
        log.invoke(logPanel, ";; Interrupting...", inputExprLogType);
        evalExpression.invoke(PersistentHashMap.create("op", "interrupt"), false);
    }
    
    public void getStdIn () {
        InputDialog dlg = new InputDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                "Input requested", String.format("A REPL expression sent to %s requires a line of *in* input:",
                        interactive.url), "", null);
        // no conditional here; what else would we do if they canceled the dialog?
        // Just a recipe for *requiring* an interrupt...?
        dlg.open();
        evalExpression.invoke(PersistentHashMap.create("op", "stdin", "stdin", dlg.getValue() + "\n"), false);
    }
    
    public void handleResponse (Response resp, String expression) {
        handleResponses.invoke(this, logPanel, expression, resp.seq());        
    }
    
    public void closeView () throws Exception {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        page.hideView(this);
        closeConnections();
    }
    
    public void closeConnections () throws Exception {
        if (interactive != null) interactive.close();
        if (toolConnection != null) toolConnection.close();
    }
    
    public void reconnect () throws Exception {
        closeConnections();
        logPanel.append(";; Reconnecting...\n");
        configure(interactive.url);
    }
    
    public void setCurrentNamespace (String ns) {
        // TODO waaaay better to put a dropdown namespace chooser in the view's toolbar,
        // and this would just change its selection
    	currentNamespace = ns;
        setPartName(String.format("REPL @ %s (%s)", interactive.url, currentNamespace));
    }
    
    public String getCurrentNamespace () {
        return currentNamespace;
    }
    
    private void prepareView () throws Exception {
        sessionId = interactive.newSession(null);
        evalExpression = (IFn)configureREPLView.invoke(this, logPanel, interactive.client, sessionId);
    }
    
    @SuppressWarnings("unchecked")
    public boolean configure (String url) throws Exception {
        try {
            // TODO — don't need multiple connections anymore, just separate sessions will do.
            interactive = new Connection(url);
            toolConnection = new Connection(url);
            setCurrentNamespace(currentNamespace);
            prepareView();
            logPanel.append(";; Clojure " + toolConnection.send("op", "eval", "code", "(clojure-version)").values().get(0) + "\n");
            return true;
        } catch (ConnectException e) {
            closeView();
            MessageDialog.openInformation(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                    "Could not connect", String.format("Could not connect to REPL @ %s", url));
            return false;
        }
    }
    
    public static REPLView connect () throws Exception {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        ConnectDialog dlg = new ConnectDialog(window.getShell());
        
        REPLView repl = null;
        if (dlg.open() == ConnectDialog.OK) {
            // cannot find any way to create a configured/connected REPLView, and install it programmatically
            String host = dlg.getHost();
            int port = dlg.getPort();
            if (host == null || host.length() == 0 || port < 0 || port > 65535) {
                MessageDialog.openInformation(window.getShell(),
                        "Invalid connection info",
                        "You must provide a useful hostname and port number to connect to a REPL.");
            } else {
                repl = connect(String.format("nrepl://%s:%s", host, port));
            }
        }
        
        return repl;
    }
    
    public static REPLView connect (String url) throws Exception {
        return connect(url, null, null);
    }
    
    public static REPLView connect (String url, IConsole console, ILaunch launch) throws Exception {
        String secondaryId;
        REPLView repl = (REPLView)PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(
                VIEW_ID,
                secondaryId = getSecondaryId(),
                IWorkbenchPage.VIEW_ACTIVATE);
        repl.secondaryId = secondaryId;
        repl.console = console;
        repl.launch = launch;
        return repl.configure(url) ? repl : null;
    }
    
    public Connection getConnection () {
        return interactive;
    }
    
    public Connection getToolingConnection () {
        return toolConnection;
    }
    
    public String getSessionId () {
        return sessionId;
    }
    
    public Set<String> getAvailableOperations () throws IllegalStateException {
        if (describeInfo == null) {
            Response r = toolConnection.send("op", "describe");
            // working around the fact that nREPL < 0.2.0-beta9 does *not* send a
            // :done status when an operation is unknown!
            // TODO remove this and just check r.statuses() after we can assume usage
            // of later versions of nREPL
            Object status = ((Map<String, String>)r.seq().first()).get(Keyword.intern("status"));
            if (clojure.lang.Util.equals(status, "unknown-op") || (status instanceof Collection &&
                    ((Collection)status).contains("error"))) {
                CCWPlugin.logError("Invalid response to \"describe\" request");
                describeInfo = new HashMap();
            } else {
                describeInfo = r.combinedResponse();
            }
        }
        
        Map<String, Object> ops = (Map<String, Object>)describeInfo.get("ops");
        return ops == null ? new HashSet() : ops.keySet();
    }
    
    /**
     * Returns the console for the launch that this REPL is associated with, or
     * null if this REPL is using a remote connection.
     */
    public IConsole getConsole () {
        return console;
    }
    
    public void showConsole () {
        if (console != null) ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
    }
    
    public ILaunch getLaunch() {
        return launch;
    }
    
    private IPreferenceStore getPreferences() {
    	return  CCWPlugin.getDefault().getCombinedPreferenceStore();
    }

    @Override
    public void createPartControl(Composite parent) {
        IPreferenceStore prefs = getPreferences();
        
        SashForm split = new SashForm(parent, SWT.VERTICAL);
        
        logPanel = new StyledText(split, SWT.V_SCROLL | SWT.WRAP);
        logPanel.setIndent(4);
        logPanel.setEditable(false);
        logPanel.setFont(JFaceResources.getFont(JFaceResources.TEXT_FONT));
        
        structuralEditionModeStatusContributionItem = ClojureSourceViewer.createStructuralEditionModeStatusContributionItem();
        viewer = new ClojureSourceViewer(split, null, null, false, SWT.V_SCROLL | SWT.H_SCROLL, prefs,
        		new ClojureSourceViewer.IStatusLineHandler() {
					public StatusLineContributionItem getEditingModeStatusContributionItem() {
						return structuralEditionModeStatusContributionItem;
					}
				}) {
        	public REPLView getCorrespondingREPL() { return REPLView.this; };
            private Connection getCorrespondingREPLConnection () {
                // we'll be connected by the time this is called
                return toolConnection;
            }
            public void setStatusLineErrorMessage(String msg) {
            	if (msg != null) {
	            	IStatusLineManager slm = (IStatusLineManager) REPLView.super.getSite().getService(IStatusLineManager.class);
	            	if (slm != null) {
	            		slm.setErrorMessage(msg);
	            	} else {
	            		MessageDialog.openError(Display.getCurrent().getActiveShell(), "REPL View status", msg);
	            	}
            	}
            };
            public String findDeclaringNamespace() {
            	String inline = super.findDeclaringNamespace();
            	if (inline != null) {
            		return inline;
            	} else {
            		return currentNamespace;
            	}
            };
        };
        viewerConfig = new ClojureSourceViewerConfiguration(prefs, viewer);
        viewer.configure(viewerConfig);
        getViewSite().setSelectionProvider(viewer);
        viewer.setDocument(ClojureDocumentProvider.configure(new Document()));
        viewerWidget = viewer.getTextWidget();
        
        viewerWidget.setFont(JFaceResources.getFont(JFaceResources.TEXT_FONT));
        
		// ensure decoration support has been created and configured.
		getSourceViewerDecorationSupport(viewer).install(prefs);


        // push all keyboard input delivered to log panel into input widget
        logPanel.addListener(SWT.KeyDown, new Listener() {
            public void handleEvent(Event e) {
                if (!(e.keyCode == SWT.PAGE_DOWN || e.keyCode == SWT.PAGE_UP)) {
                    // this prevents focus switch on cut/copy
                    // no event that would trigger a paste is sent as long as logPanel is uneditable,
                    // so we can't redirect it :-(
                    boolean modifier = (e.keyCode & SWT.MODIFIER_MASK) != 0;
                    if (modifier) return;
                    viewerWidget.notifyListeners(SWT.KeyDown, e);
                    viewerWidget.setFocus();
                }
            }
        });
        
        // page up/down in input area should control log
        viewerWidget.setKeyBinding(SWT.PAGE_DOWN, SWT.NULL);
        viewerWidget.setKeyBinding(SWT.PAGE_UP, SWT.NULL);
        viewerWidget.addListener(SWT.KeyDown, new Listener () {
           public void handleEvent (Event e) {
               switch (e.keyCode) {
                   case SWT.PAGE_DOWN:
                       logPanel.invokeAction(ST.PAGE_DOWN);
                       break;
                   case SWT.PAGE_UP:
                       logPanel.invokeAction(ST.PAGE_UP);
                       break;
               }
           }
        });
        
        installMessageDisplayer(viewerWidget, new MessageProvider() {
			public String getMessageText() {
				return getEvaluationHint();
			}
		});
        
        installAutoEvalExpressionOnEnter();

        installEvalTopLevelSExpressionCommand();
        
        /*
         * Need to hook up here to force a re-evaluation of the preferences
         * for the syntax coloring, after the token scanner has been
         * initialized. Otherwise the very first Clojure editor will not
         * have any tokens colored.
         * TODO this is repeated in ClojureEditor...surely we can make the source viewer self-sufficient here 
         */
        viewer.propertyChange(null);
        
        viewerWidget.addFocusListener(new NamespaceRefreshFocusListener());
        
        logPanel.addFocusListener(new NamespaceRefreshFocusListener());
        
        parent.addDisposeListener(new DisposeListener () {
            public void widgetDisposed(DisposeEvent e) {
                activeREPL.compareAndSet(REPLView.this, null);
            }
        });
        
        /*
         * TODO find a way for the following code line to really work. That is add
         * the necessary additional code for enabling "handlers" (in fact, my fear
         * is that those really are not handlers but "actions" that will need to be
         * manually enabled as I did above for EVALUATE_TOP_LEVEL_S_EXPRESSION :-( )
         */
        ((IContextService) getSite().getService(IContextService.class)).activateContext("org.eclipse.ui.textEditorScope");

        /* Thought just activating CCW_UI_CONTEXT_REPL would also activate its parent contexts
         * but apparently not, so here we activate explicitly all the contexts we want (FIXME?)
         */ 
        ((IContextService) getSite().getService(IContextService.class)).activateContext(IClojureEditor.KEY_BINDING_SCOPE); 
        ((IContextService) getSite().getService(IContextService.class)).activateContext(CCW_UI_CONTEXT_REPL);
        
        split.setWeights(new int[] {100, 75});
        
        getViewSite().getActionBars().getStatusLineManager().add(this.structuralEditionModeStatusContributionItem);
        viewer.updateStructuralEditingModeStatusField();
        structuralEditionModeStatusContributionItem.setActionHandler(new Action() {
    		public void run() {
				viewer.toggleStructuralEditionMode();
			}
    	});
        
       
        resetFont();
        JFaceResources.getFontRegistry().addListener(fontChangeListener);
    }
    
    private interface MessageProvider {
    	String getMessageText();
    }
    
    private void installMessageDisplayer(final StyledText textViewer, final MessageProvider hintProvider) {
		textViewer.addListener(SWT.Paint, new Listener() {
		    private int getScrollbarAdjustment () {
		        if (!Platform.getOS().equals(Platform.OS_MACOSX)) return 0;
		        
		        // You cannot reliably determine if a scrollbar is visible or not
                //   (http://stackoverflow.com/questions/5674207)
                // So, we need to determine if the vertical scrollbar is "needed" based
                // on the contents in the viewer
                Rectangle clientArea = textViewer.getClientArea();
                int textLength = textViewer.getText().length();
                if (textLength == 0 || textViewer.getTextBounds(0, Math.max(0, textLength - 1)).height < clientArea.height) {
                    return textViewer.getVerticalBar().getSize().x;
                } else {
                    return 0;
                }
		    }
		    
			public void handleEvent(Event event) {
				String message = hintProvider.getMessageText();
				if (message == null) 
					return;

                // keep the 'tooltip' using the default font 
                event.gc.setFont(JFaceResources.getFont(JFaceResources.DEFAULT_FONT));

				Point topRightPoint = topRightPoint(textViewer.getClientArea());
				int sWidth = textWidthPixels(message, event);
				int x = Math.max(topRightPoint.x - sWidth + getScrollbarAdjustment(), 0);
				int y = topRightPoint.y;
				
				// the text widget doesn't know we're painting the hint, so it won't necessarily
				// clear old presentations of it; this leads to streaking on Windows if we don't
				// clear the foreground explicitly
				Color fg = event.gc.getForeground();
				event.gc.setForeground(event.gc.getBackground());
				event.gc.drawRectangle(textViewer.getClientArea());
				event.gc.setForeground(fg);
				event.gc.setAlpha(200);
				event.gc.drawText(message, x, y, true);
			}

			private Point topRightPoint(Rectangle clipping) {
				return new Point(clipping.x + clipping.width, clipping.y);
			}

			private int textWidthPixels(String text, Event evt) {
				int width = 0;
				for (int i = 0; i < text.length(); i++) {
					width += evt.gc.getAdvanceWidth(text.charAt(i));
				}
				return width;
			}
		});
    }
    
    private String getEvaluationHint() {
    	if (!getPreferences().getBoolean(PreferenceConstants.REPL_VIEW_DISPLAY_HINTS))
    		return null;
    	
    	if (getPreferences().getBoolean(PreferenceConstants.REPL_VIEW_AUTO_EVAL_ON_ENTER_ACTIVE)) {
    		return Messages.REPLView_autoEval_on_Enter_active;
    	} else {
    		return Messages.format(Messages.REPLView_autoEval_on_Enter_inactive,
    				Platform.getOS().equals(Platform.OS_MACOSX)
    					? "Cmd"
    				    : "Ctrl");
    	}
    }
    
    private void installAutoEvalExpressionOnEnter() {
        viewerWidget.addVerifyKeyListener(new VerifyKeyListener() {
        	private boolean enterAlonePressed(VerifyEvent e) {
        		return (e.keyCode == SWT.LF || e.keyCode == SWT.CR)
						&& e.stateMask == SWT.NONE;
        	}
        	private boolean noSelection() {
        		return viewerWidget.getSelectionCount() == 0;
        	}
        	private String textAfterCaret() {
        		return viewerWidget.getText().substring(
        				viewerWidget.getSelection().x);
        	}
        	private boolean isAutoEvalOnEnterAllowed() {
        		return getPreferences().getBoolean(PreferenceConstants.REPL_VIEW_AUTO_EVAL_ON_ENTER_ACTIVE);
        	}
			public void verifyKey(VerifyEvent e) {
				if (    isAutoEvalOnEnterAllowed()
						&& enterAlonePressed(e) 
						&& noSelection() 
						&& textAfterCaret().trim().isEmpty()
						&& !viewer.isParseTreeBroken()) {
					
					final String widgetText = viewerWidget.getText();
					
					// Executing evalExpression() via SWT's asyncExec mechanism,
					// we ensure all the normal behaviour is done by the Eclipse
					// framework on the Enter key, before sending the code.
					// For example, we are then able to get rid of a bug with
					// the content assistant which ensures the text is completed
					// with the selection before being sent for evaluation.
					DisplayUtil.asyncExec(new Runnable() {
						public void run() {
							// we do not execute auto eval if some non-blank text has
							// been added between the check and the execution
							final String text = viewerWidget.getText();
							int idx = text.indexOf(widgetText);
							if (idx == 0 && text.substring(widgetText.length()).trim().isEmpty()) {
								evalExpression();
							}
						}});
				} 
			}
        });
    }
    private void installEvalTopLevelSExpressionCommand() {
        IHandlerService handlerService = (IHandlerService) getViewSite().getService(IHandlerService.class);
        handlerService.activateHandler(IClojureEditorActionDefinitionIds.EVALUATE_TOP_LEVEL_S_EXPRESSION, new AbstractHandler() {
    		public Object execute(ExecutionEvent event) throws ExecutionException {
    			evalExpression();
    			return null;
    		}
    	});
    }
    
	/**
	 * Returns the source viewer decoration support.
	 *
	 * @param viewer the viewer for which to return a decoration support
	 * @return the source viewer decoration support
	 */
    // From ClojureEditor + AbstractDecoratedTextEditor ...
	protected SourceViewerDecorationSupport getSourceViewerDecorationSupport(ISourceViewer viewer) {
		if (fSourceViewerDecorationSupport == null) {
			fSourceViewerDecorationSupport= new SourceViewerDecorationSupport(
					viewer, 
					null/*getOverviewRuler()*/, 
					null/*getAnnotationAccess()*/, 
					EditorsPlugin.getDefault().getSharedTextColors()/*getSharedColors()*/
					);
			editorSupport._("configureSourceViewerDecorationSupport",
					fSourceViewerDecorationSupport, viewer);
		}
		return fSourceViewerDecorationSupport;
	}

    @Override
    public void dispose() {
        super.dispose();

        releaseSecondaryId(secondaryId);
        
        fSourceViewerDecorationSupport = (SourceViewerDecorationSupport) editorSupport._("disposeSourceViewerDecorationSupport",
        		fSourceViewerDecorationSupport);
        try {
            if (interactive != null) interactive.close();
            if (toolConnection != null) toolConnection.close();
        } catch (IOException e) {
            CCWPlugin.logError(e);
        }
        JFaceResources.getFontRegistry().removeListener(fontChangeListener);
    }

    public boolean isDisposed () {
        // TODO we actually want to report whether the viewpart has been closed, not whether or not
        // the platform has disposed the widget peer
        return viewerWidget.isDisposed();
    }

    @Override
    public void setFocus() {
        viewerWidget.setFocus();
    }

    private final class NamespaceRefreshFocusListener implements FocusListener {
        public void focusGained(FocusEvent e) {
            activeREPL.set(REPLView.this);
            NamespaceBrowser.setREPLConnection(toolConnection);
        }

        public void focusLost(FocusEvent e) {}
    }

    @Override
    public Object getAdapter(Class adapter) {
    	if (adapter == IClojureEditor.class) {
    		return viewer;
    	} else {
    		return super.getAdapter(adapter);
    	}
    }
    
}
