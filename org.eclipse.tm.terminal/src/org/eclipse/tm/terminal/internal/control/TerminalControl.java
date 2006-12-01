/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems, Inc. - initial implementation
 *     
 *******************************************************************************/

package org.eclipse.tm.terminal.internal.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.ConfigurableLineTracker;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.tm.terminal.ITerminalConnector;
import org.eclipse.tm.terminal.ITerminalControl;
import org.eclipse.tm.terminal.Logger;
import org.eclipse.tm.terminal.TerminalState;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.tm.terminal.control.ITerminalListener;
import org.eclipse.tm.terminal.control.ITerminalViewControl;

/**
 *
 * This class was originally written to use nested classes, which unfortunately makes
 * this source file larger and more complex than it needs to be.  In particular, the
 * methods in the nested classes directly access the fields of the enclosing class.
 * One day we should pull the nested classes out into their own source files (but still
 * in this package).
 *
 * @author Chris Thew <chris.thew@windriver.com>
 */
public class TerminalControl implements ITerminalControlForText, ITerminalControl, ITerminalViewControl
{
    protected final static String[] LINE_DELIMITERS = { "\n" }; //$NON-NLS-1$

    /**
     * This field holds a reference to a TerminalText object that performs all ANSI
     * text processing on data received from the remote host and controls how text is
     * displayed using the view's StyledText widget.
     */
    private TerminalText              fTerminalText;

    private Display                   fDisplay;
    private StyledText                fCtlText;
    private TextViewer                fViewer;
    private Composite                 fWndParent;
    private Clipboard                 fClipboard;
    private TerminalModifyListener    fModifyListener;
    private KeyListener               fKeyHandler;
    private ITerminalListener         fTerminalListener;
    private String                    fMsg = ""; //$NON-NLS-1$
    private VerifyKeyListener         fVerifyKeyListener;
    private FocusListener             fFocusListener;
    private ITerminalConnector		  fConnector;
    private final ITerminalConnector[]      fConnectors;

	private volatile TerminalState fState;

	public TerminalControl(ITerminalListener target, Composite wndParent, ITerminalConnector[] connectors) {
		fConnectors=connectors;
		fTerminalListener=target;
		fWndParent = wndParent;

		setTerminalText(new TerminalText(this));

		setupTerminal();
	}

	public ITerminalConnector[] getConnectors() {
		return fConnectors;
	}
	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#copy()
	 */
	public void copy() {
		getCtlText().copy();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#paste()
	 */
	public void paste() {
		TextTransfer textTransfer = TextTransfer.getInstance();
		String strText = (String) fClipboard.getContents(textTransfer);
		if (strText == null)
			return;
		for (int i = 0; i < strText.length(); i++) {
			sendChar(strText.charAt(i), false);
		}
// TODO paste in another thread.... to avoid blocking
//		new Thread() {
//			public void run() {
//				for (int i = 0; i < strText.length(); i++) {
//					sendChar(strText.charAt(i), false);
//				}
//				
//			}
//		}.start();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#selectAll()
	 */
	public void selectAll() {
		getCtlText().selectAll();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#sendKey(char)
	 */
	public void sendKey(char character) {
		Event event;
		KeyEvent keyEvent;

		event = new Event();
		event.widget = getCtlText();
		event.character = character;
		event.keyCode = 0;
		event.stateMask = 0;
		event.doit = true;
		keyEvent = new KeyEvent(event);

		fKeyHandler.keyPressed(keyEvent);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#clearTerminal()
	 */
	public void clearTerminal() {
		// The TerminalText object does all text manipulation.

		getTerminalText().clearTerminal();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#getClipboard()
	 */
	public Clipboard getClipboard() {
		return fClipboard;
	}

	/**
	 * @return non null selection
	 */
	public String getSelection() {
		String txt= ((ITextSelection) fViewer.getSelection()).getText();
		if(txt==null)
			txt=""; //$NON-NLS-1$
		return txt;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#setFocus()
	 */
	public void setFocus() {
		getCtlText().setFocus();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#isEmpty()
	 */
	public boolean isEmpty() {
		return (getCtlText().getCharCount() == 0);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#isDisposed()
	 */
	public boolean isDisposed() {
		return getCtlText().isDisposed();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#isConnected()
	 */
	public boolean isConnected() {
		return fState==TerminalState.CONNECTED;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#disposeTerminal()
	 */
	public void disposeTerminal() {
		Logger.log("entered."); //$NON-NLS-1$
		disconnectTerminal();
		fClipboard.dispose();
		getTerminalText().dispose();
	}

	public void connectTerminal() {
		Logger.log("entered."); //$NON-NLS-1$
		if(fConnector==null)
			return;
		fConnector.connect(this);
		waitForConnect();
	}
	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#disconnectTerminal()
	 */
	public void disconnectTerminal() {
		Logger.log("entered."); //$NON-NLS-1$

		if (getState()==TerminalState.CLOSED) {
			return;
		}
		if(fConnector!=null) {
			fConnector.disconnect();
//			fConnector=null;
		}
	}
	// TODO
	private void waitForConnect() {
		Logger.log("entered."); //$NON-NLS-1$
		// TODO 
		// Eliminate this code
		while (getState()==TerminalState.CONNECTING) {
			if (fDisplay.readAndDispatch())
				continue;

			fDisplay.sleep();
		}
		if (!getMsg().equals("")) //$NON-NLS-1$
		{
			String strTitle = TerminalMessages.TerminalError;
			MessageDialog.openError( getShell(), strTitle, getMsg());
	
			disconnectTerminal();
			return;
		}
	
		getCtlText().setFocus();
	}

	protected void sendString(String string) {
		try {
			// Send the string after converting it to an array of bytes using the
			// platform's default character encoding.
			//
			// TODO: Find a way to force this to use the ISO Latin-1 encoding.

			getOutputStream().write(string.getBytes());
			getOutputStream().flush();
		} catch (SocketException socketException) {
			displayTextInTerminal(socketException.getMessage());

			String strTitle = TerminalMessages.TerminalError;
			String strMsg = TerminalMessages.SocketError
					+ "!\n" + socketException.getMessage(); //$NON-NLS-1$

			MessageDialog.openError(getShell(), strTitle, strMsg);
			Logger.logException(socketException);

			disconnectTerminal();
		} catch (IOException ioException) {
			displayTextInTerminal(ioException.getMessage());

			String strTitle = TerminalMessages.TerminalError;
			String strMsg = TerminalMessages.IOError + "!\n" + ioException.getMessage(); //$NON-NLS-1$

			MessageDialog.openError(getShell(), strTitle, strMsg);
			Logger.logException(ioException);

			disconnectTerminal();
		}
	}

	public Shell getShell() {
		return getCtlText().getShell();
	}

	protected void sendChar(char chKey, boolean altKeyPressed) {
		try {
			int byteToSend = chKey;

			if (altKeyPressed) {
				// When the ALT key is pressed at the same time that a character is
				// typed, translate it into an ESCAPE followed by the character.  The
				// alternative in this case is to set the high bit of the character
				// being transmitted, but that will cause input such as ALT-f to be
				// seen as the ISO Latin-1 character '�', which can be confusing to
				// European users running Emacs, for whom Alt-f should move forward a
				// word instead of inserting the '�' character.
				//
				// TODO: Make the ESCAPE-vs-highbit behavior user configurable.

				Logger.log("sending ESC + '" + byteToSend + "'"); //$NON-NLS-1$ //$NON-NLS-2$
				getOutputStream().write('\u001b');
				getOutputStream().write(byteToSend);
			} else {
				Logger.log("sending '" + byteToSend + "'"); //$NON-NLS-1$ //$NON-NLS-2$
				getOutputStream().write(byteToSend);
			}

			getOutputStream().flush();
		} catch (SocketException socketException) {
			Logger.logException(socketException);

			displayTextInTerminal(socketException.getMessage());

			String strTitle = TerminalMessages.TerminalError;
			String strMsg = TerminalMessages.SocketError
					+ "!\n" + socketException.getMessage(); //$NON-NLS-1$

			MessageDialog.openError(getShell(), strTitle, strMsg);
			Logger.logException(socketException);

			disconnectTerminal();
		} catch (IOException ioException) {
			Logger.logException(ioException);

			displayTextInTerminal(ioException.getMessage());

			String strTitle = TerminalMessages.TerminalError;
			String strMsg = TerminalMessages.IOError + "!\n" + ioException.getMessage(); //$NON-NLS-1$

			MessageDialog.openError(getShell(), strTitle, strMsg);
			Logger.logException(ioException);

			disconnectTerminal();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#setupTerminal()
	 */
	public void setupTerminal() {
		fState=TerminalState.CLOSED;
		setupControls();
		setupListeners();
		setupHelp(fWndParent, TerminalPlugin.HELP_VIEW);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#onFontChanged()
	 */
	public void onFontChanged() {
		getTerminalText().fontChanged();
	}

	protected void setupControls() {
		// The Terminal view now aims to be an ANSI-conforming terminal emulator, so it
		// can't have a horizontal scroll bar (but a vertical one is ok).  Also, do
		// _not_ make the TextViewer read-only, because that prevents it from seeing a
		// TAB character when the user presses TAB (instead, the TAB causes focus to
		// switch to another Workbench control).  We prevent local keyboard input from
		// modifying the text in method TerminalVerifyKeyListener.verifyKey().

		fViewer = new TextViewer(fWndParent, SWT.V_SCROLL);
		setCtlText(fViewer.getTextWidget());

		fDisplay = getCtlText().getDisplay();
		fClipboard = new Clipboard(fDisplay);
		fViewer.setDocument(new TerminalDocument());
		getCtlText().setFont(JFaceResources.getTextFont());
	}

	protected void setupListeners() {
		fKeyHandler = new TerminalKeyHandler();
		fModifyListener = new TerminalModifyListener();
		fVerifyKeyListener = new TerminalVerifyKeyListener();
		fFocusListener = new TerminalFocusListener();

		getCtlText().addVerifyKeyListener(fVerifyKeyListener);
		getCtlText().addKeyListener(fKeyHandler);
		getCtlText().addModifyListener(fModifyListener);
		getCtlText().addVerifyKeyListener(fVerifyKeyListener);
		getCtlText().addFocusListener(fFocusListener);
	}

	/**
	 * Setup all the help contexts for the controls.
	 */
	protected void setupHelp(Composite parent, String id) {
		Control[] children = parent.getChildren();

		for (int nIndex = 0; nIndex < children.length; nIndex++) {
			if (children[nIndex] instanceof Composite) {
				setupHelp((Composite) children[nIndex], id);
			}
		}

		PlatformUI.getWorkbench().getHelpSystem().setHelp(parent, id);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#displayTextInTerminal(java.lang.String)
	 */
	public void displayTextInTerminal(String text) {
		writeToTerminal(text+"\r\n"); //$NON-NLS-1$
	}

	public void writeToTerminal(String txt) {

		// Do _not_ use asyncExec() here.  Class TerminalText requires that
		// its run() and setNewText() methods be called in strictly
		// alternating order.  If we were to call asyncExec() here, this
		// loop might race around and call setNewText() twice in a row,
		// which would lose data.
		getTerminalText().setNewText(new StringBuffer(txt));
		if(Display.getDefault().getThread()==Thread.currentThread())
			getTerminalText().run();
		else
			fDisplay.syncExec(getTerminalText());
		
	}

	protected boolean isLogCharEnabled() {
		return TerminalPlugin.isOptionEnabled(Logger.TRACE_DEBUG_LOG_CHAR);
	}
	protected boolean isLogBufferSizeEnabled() {
		return TerminalPlugin
				.isOptionEnabled(Logger.TRACE_DEBUG_LOG_BUFFER_SIZE);
	}


	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#getInputStream()
	 */
	public InputStream getInputStream() {
		if(fConnector!=null)
			return fConnector.getInputStream();
		return null;
	}

	public OutputStream getOutputStream() {
		if(fConnector!=null)
			return fConnector.getOutputStream();
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#setMsg(java.lang.String)
	 */
	public void setMsg(String msg) {
		fMsg = msg;
	}

	public String getMsg() {
		return fMsg;
	}

	void setCtlText(StyledText ctlText) {
		fCtlText = ctlText;
		fTerminalText.setStyledText(ctlText);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#getCtlText()
	 */
	public StyledText getCtlText() {
		return fCtlText;
	}

	void setTerminalText(TerminalText terminalText) {
		fTerminalText = terminalText;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tm.terminal.ITerminalControl#getTerminalText()
	 */
	public TerminalText getTerminalText() {
		return fTerminalText;
	}

	/**
	 */
	public ITerminalConnector getTerminalConnection() {
		return fConnector;
	}

	protected class TerminalModifyListener implements ModifyListener {
		public void modifyText(ModifyEvent e) {
			if (e.getSource() instanceof StyledText) {
				StyledText text = (StyledText) e.getSource();
				text.setSelection(text.getText().length());
			}
		}
	}

	protected class TerminalFocusListener implements FocusListener {
		private IContextActivation contextActivation = null;

		protected TerminalFocusListener() {
			super();
		}

		public void focusGained(FocusEvent event) {
			// Disable all keyboard accelerators (e.g., Control-B) so the Terminal view
			// can see every keystroke.  Without this, Emacs, vi, and Bash are unusable
			// in the Terminal view.

			IBindingService bindingService = (IBindingService) PlatformUI
					.getWorkbench().getAdapter(IBindingService.class);
			bindingService.setKeyFilterEnabled(false);

			// The above code fails to cause Eclipse to disable menu-activation
			// accelerators (e.g., Alt-F for the File menu), so we set the command
			// context to be the Terminal view's command context.  This enables us to
			// override menu-activation accelerators with no-op commands in our
			// plugin.xml file, which enables the Terminal view to see absolutly _all_
			// key-presses.

			IContextService contextService = (IContextService) PlatformUI
					.getWorkbench().getAdapter(IContextService.class);
			contextActivation = contextService
					.activateContext("org.eclipse.tm.terminal.TerminalContext"); //$NON-NLS-1$
		}

		public void focusLost(FocusEvent event) {
			// Enable all keybindings.

			IBindingService bindingService = (IBindingService) PlatformUI
					.getWorkbench().getAdapter(IBindingService.class);
			bindingService.setKeyFilterEnabled(true);

			// Restore the command context to its previous value.

			IContextService contextService = (IContextService) PlatformUI
					.getWorkbench().getAdapter(IContextService.class);
			contextService.deactivateContext(contextActivation);
		}
	}

	protected class TerminalVerifyKeyListener implements VerifyKeyListener {
		public void verifyKey(VerifyEvent event) {
			// We set event.doit to false to prevent keyboard input from locally
			// modifying the contents of the StyledText widget.  The only text we
			// display is text received from the remote endpoint.  This also prevents
			// the caret from moving locally when the user presses an arrow key or the
			// PageUp or PageDown keys.  For some reason, doing this in
			// TerminalKeyHandler.keyPressed() does not work, hence the need for this
			// class.

			event.doit = false;
		}
	}
	protected class TerminalKeyHandler extends KeyAdapter {
		public void keyPressed(KeyEvent event) {
			if (getState()==TerminalState.CONNECTING)
				return;

			// We set the event.doit to false to prevent any further processing of this
			// key event.  The only reason this is here is because I was seeing the F10
			// key both send an escape sequence (due to this method) and switch focus
			// to the Workbench File menu (forcing the user to click in the Terminal
			// view again to continue entering text).  This fixes that.

			event.doit = false;

			char character = event.character;

			if (!isConnected()) {
				// Pressing ENTER while not connected causes us to connect.
				if (character == '\r') {
					connectTerminal();
					return;
				}

				// Ignore all other keyboard input when not connected.
				return;
			}

			// If the event character is NUL ('\u0000'), then a special key was pressed
			// (e.g., PageUp, PageDown, an arrow key, a function key, Shift, Alt,
			// Control, etc.).  The one exception is when the user presses Control-@,
			// which sends a NUL character, in which case we must send the NUL to the
			// remote endpoint.  This is necessary so that Emacs will work correctly,
			// because Control-@ (i.e., NUL) invokes Emacs' set-mark-command when Emacs
			// is running on a terminal.  When the user presses Control-@, the keyCode
			// is 50.

			if (character == '\u0000' && event.keyCode != 50) {
				// A special key was pressed.  Figure out which one it was and send the
				// appropriate ANSI escape sequence.
				//
				// IMPORTANT: Control will not enter this method for these special keys
				// unless certain <keybinding> tags are present in the plugin.xml file
				// for the Terminal view.  Do not delete those tags.

				switch (event.keyCode) {
				case 0x1000001: // Up arrow.
					sendString("\u001b[A"); //$NON-NLS-1$
					break;

				case 0x1000002: // Down arrow.
					sendString("\u001b[B"); //$NON-NLS-1$
					break;

				case 0x1000003: // Left arrow.
					sendString("\u001b[D"); //$NON-NLS-1$
					break;

				case 0x1000004: // Right arrow.
					sendString("\u001b[C"); //$NON-NLS-1$
					break;

				case 0x1000005: // PgUp key.
					sendString("\u001b[I"); //$NON-NLS-1$
					break;

				case 0x1000006: // PgDn key.
					sendString("\u001b[G"); //$NON-NLS-1$
					break;

				case 0x1000007: // Home key.
					sendString("\u001b[H"); //$NON-NLS-1$
					break;

				case 0x1000008: // End key.
					sendString("\u001b[F"); //$NON-NLS-1$
					break;

				case 0x100000a: // F1 key.
					sendString("\u001b[M"); //$NON-NLS-1$
					break;

				case 0x100000b: // F2 key.
					sendString("\u001b[N"); //$NON-NLS-1$
					break;

				case 0x100000c: // F3 key.
					sendString("\u001b[O"); //$NON-NLS-1$
					break;

				case 0x100000d: // F4 key.
					sendString("\u001b[P"); //$NON-NLS-1$
					break;

				case 0x100000e: // F5 key.
					sendString("\u001b[Q"); //$NON-NLS-1$
					break;

				case 0x100000f: // F6 key.
					sendString("\u001b[R"); //$NON-NLS-1$
					break;

				case 0x1000010: // F7 key.
					sendString("\u001b[S"); //$NON-NLS-1$
					break;

				case 0x1000011: // F8 key.
					sendString("\u001b[T"); //$NON-NLS-1$
					break;

				case 0x1000012: // F9 key.
					sendString("\u001b[U"); //$NON-NLS-1$
					break;

				case 0x1000013: // F10 key.
					sendString("\u001b[V"); //$NON-NLS-1$
					break;

				case 0x1000014: // F11 key.
					sendString("\u001b[W"); //$NON-NLS-1$
					break;

				case 0x1000015: // F12 key.
					sendString("\u001b[X"); //$NON-NLS-1$
					break;

				default:
					// Ignore other special keys.  Control flows through this case when
					// the user presses SHIFT, CONTROL, ALT, and any other key not
					// handled by the above cases.
					break;
				}

				// It's ok to return here, because we never locally echo special keys.

				return;
			}

			// To fix SPR 110341, we consider the Alt key to be pressed only when the
			// Control key is _not_ also pressed.  This works around a bug in SWT where,
			// on European keyboards, the AltGr key being pressed appears to us as Control
			// + Alt being pressed simultaneously.

			Logger.log("stateMask = " + event.stateMask); //$NON-NLS-1$

			boolean altKeyPressed = (((event.stateMask & SWT.ALT) != 0) && ((event.stateMask & SWT.CTRL) == 0));

			if (!altKeyPressed && (event.stateMask & SWT.CTRL) != 0
					&& character == ' ') {
				// Send a NUL character -- many terminal emulators send NUL when
				// Control-Space is pressed.  This is used to set the mark in Emacs.

				character = '\u0000';
			}

			sendChar(character, altKeyPressed);

			// Special case: When we are in a TCP connection and echoing characters
			// locally, send a LF after sending a CR.
			// ISSUE: Is this absolutely required?

			if (character == '\r' && getTerminalConnection() != null
					&& isConnected()
					&& getTerminalConnection().isLocalEcho()) {
				sendChar('\n', false);
			}

			// Now decide if we should locally echo the character we just sent.  We do
			// _not_ locally echo the character if any of these conditions are true:
			//
			// o This is a serial connection.
			//
			// o This is a TCP connection (i.e., m_telnetConnection is not null) and
			//   the remote endpoint is not a TELNET server.
			//
			// o The ALT (or META) key is pressed.
			//
			// o The character is any of the first 32 ISO Latin-1 characters except
			//   Control-I or Control-M.
			//
			// o The character is the DELETE character.

			if (getTerminalConnection() == null
					|| getTerminalConnection().isLocalEcho() == false || altKeyPressed
					|| (character >= '\u0001' && character < '\t')
					|| (character > '\t' && character < '\r')
					|| (character > '\r' && character <= '\u001f')
					|| character == '\u007f') {
				// No local echoing.
				return;
			}

			// Locally echo the character.

			StringBuffer charBuffer = new StringBuffer();
			charBuffer.append(character);

			// If the character is a carriage return, we locally echo it as a CR + LF
			// combination.

			if (character == '\r')
				charBuffer.append('\n');

			writeToTerminal(charBuffer.toString());
		}

	}

	protected class TerminalDocument extends Document {
		protected TerminalDocument() {
			setLineTracker(new ConfigurableLineTracker(LINE_DELIMITERS));
		}
	}

	public void setTerminalTitle(String title) {
		fTerminalListener.setTerminalTitle(title);
	}


	public TerminalState getState() {
		return fState;
	}


	public void setState(TerminalState state) {
		fState=state;
		fTerminalListener.setState(state);
	}

	public String getStatusString(String strConnected) {
		if(fConnector!=null)
			return fConnector.getStatusString(strConnected);
		return strConnected;
	}

	public void setConnector(ITerminalConnector connector) {
		fConnector=connector;
		
	}
}
