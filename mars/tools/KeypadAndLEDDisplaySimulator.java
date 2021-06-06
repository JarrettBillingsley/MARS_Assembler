package mars.tools;

import javax.swing.*;
import javax.swing.plaf.basic.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

import mars.Globals;
import mars.mips.hardware.*;
import mars.simulator.Exceptions;

/*
 Copyright (c) 2009 Jose Baiocchi

 Developed by Jose Baiocchi (baiocchi@cs.pitt.edu)

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject
 to the following conditions:

 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

 (MIT license, http://www.opensource.org/licenses/mit-license.html)
 */
/**
 * LED Display Simulator. It can be run either as a stand-alone Java application
 * having access to the mars package, or through MARS as an item in its Tools
 * menu. It makes maximum use of methods inherited from its abstract superclass
 * AbstractMarsToolAndApplication.
 *
 * @author Jose Baiocchi
 * @version 1.1. 16 February 2010.
 */
public class KeypadAndLEDDisplaySimulator extends AbstractMarsToolAndApplication
{

	static final long serialVersionUID = 1; /* To eliminate a warning about serializability. */

	private static String version = "Version 1.3 64x64";
	private static String title = "Keypad and LED Display MMIO Simulator";
	private static String heading = "Click this window and use keypad";
	private static final int N_COLUMNS = 64;
	private static final int N_ROWS = 64;
	private static final int SCREEN_UPDATE = Memory.memoryMapBaseAddress;
	private static final int KEY_STATE = SCREEN_UPDATE + Memory.WORD_LENGTH_BYTES;
	private static final int LED_START = KEY_STATE + Memory.WORD_LENGTH_BYTES;
	private static final int LED_END = LED_START + N_ROWS * N_COLUMNS;

	// the 4096 is there to give a "buffer zone" between the user-written area and the
	// display buffer. this way, writes past the end of the display will be invisible.
	// ...it's just to give the students a little leeway. :P
	private static final int LED_BUFFER_START = LED_END + 4096;
	private static final int LED_BUFFER_END = LED_BUFFER_START + N_ROWS * N_COLUMNS;
	private JPanel panel;
	private LEDDisplayPanel displayPanel;
	private int keyState;

	//default
	private static final int KEY_U = 1;
	private static final int KEY_D = 2;
	private static final int KEY_L = 4;
	private static final int KEY_R = 8;
	private static final int KEY_B = 16;
	private static final int KEY_Z = 32;
	private static final int KEY_X = 64;
	private static final int KEY_C = 128;

	//top of keypad
	private static final int KEY_1 = 256;
	private static final int KEY_2 = 512;
	private static final int KEY_3 = 1024;
	private static final int KEY_4 = 2048;
	private static final int KEY_Q = 4096;
	private static final int KEY_W = 8192;
	private static final int KEY_E = 16384;
	//yes i am aware this is a terrible variable name. sue me.
	private static final int KEY_R1 = 32768;

	//bottom of keypad
	private static final int KEY_A = 65536;
	private static final int KEY_S = 131072;
	private static final int KEY_D1 = 262144;
	private static final int KEY_F = 524288;
	private static final int KEY_V = 1048576;


	//CH - So basically, I need a 16 key keypad for a personal project. To achieve this, I'm adding bindings for the
	// following keys
	/*
	 ┌─┬─┬─┬─┐
	 │1│2│3│4│
	 ├─┼─┼─┼─┤
	 │q│w│e│r│
	 ├─┼─┼─┼─┤
	 │a│s│d│f│
	 ├─┼─┼─┼─┤
	 │z│x│c│v│
	 └─┴─┴─┴─┘
	 */
	//you might notice the bottom row of keys is a duplicate of the default keys. To avoid ambiguity, they use the same
	//actionmapper value, but are simply written at a different location in memory to allow writing the entire keypad
	//easier


	public KeypadAndLEDDisplaySimulator(String title, String heading)
	{
		super(title, heading);
	}

	public KeypadAndLEDDisplaySimulator()
	{
		super(title + ", " + version, heading);
	}

	public static void main(String[] args)
	{
		new KeypadAndLEDDisplaySimulator(title + " stand-alone, " + version,
										 heading).go();
	}

	public String getName()
	{
		return "Keypad and LED Display Simulator";
	}

	protected void addAsObserver()
	{
		addAsObserver(SCREEN_UPDATE, KEY_STATE);
	}

	protected JComponent buildMainDisplayArea()
	{
		panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		displayPanel = new LEDDisplayPanel();

		JPanel subPanel = new JPanel();
		JCheckBox gridCheckBox = new JCheckBox("Show Grid Lines");
		gridCheckBox.addItemListener((e) -> {
			displayPanel.setGridLinesEnabled(e.getStateChange() == ItemEvent.SELECTED);
			displayPanel.revalidate();
			this.theWindow.pack();
			displayPanel.repaint();
		});
		subPanel.add(gridCheckBox);

		JCheckBox zoomCheckBox = new JCheckBox("Zoom");
		zoomCheckBox.addItemListener((e) -> {
			displayPanel.setZoomed(e.getStateChange() == ItemEvent.SELECTED);
			displayPanel.revalidate();
			this.theWindow.pack();
			displayPanel.repaint();
		});
		subPanel.add(zoomCheckBox);

		panel.add(subPanel);
		panel.add(displayPanel);

		/* This is so hacky and verbose. I don't know of a better way. */
		// JB: I feel you, Jose. "Verbose" is the best adjective for Java.
		// CH: you were both right. I was a fool. Existence is pain.

		//default binds
		InputMap map = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0, false), "LeftKeyPressed"   );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0, false), "RightKeyPressed"  );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP,    0, false), "UpKeyPressed"     );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN,  0, false), "DownKeyPressed"   );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_B,     0, false), "BKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,     0, false), "ZKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_X,     0, false), "XKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_C,     0, false), "CKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0, true ), "LeftKeyReleased"  );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0, true ), "RightKeyReleased" );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP,    0, true ), "UpKeyReleased"    );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN,  0, true ), "DownKeyReleased"  );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_B,     0, true ), "BKeyReleased"     );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,     0, true ), "ZKeyReleased"     );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_X,     0, true ), "XKeyReleased"     );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_C,     0, true ), "CKeyReleased"     );

		//top of keypad
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_1,  0, false), "1KeyPressed"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0, false), "2KeyPressed"          );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_3,    0, false), "3KeyPressed"       );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_4,  0, false), "4KeyPressed"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q,     0, false), "QKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_W,     0, false), "WKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_E,     0, false), "EKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_R,     0, false), "RKeyPressed"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_1,  0, true), "1KeyReleased"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0, true), "2KeyReleased"          );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_3,    0, true), "3KeyReleased"       );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_4,  0, true), "4KeyReleased"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q,     0, true), "QKeyReleased"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_W,     0, true), "WKeyReleased"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_E,     0, true), "EKeyReleased"      );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_R,     0, true), "RKeyReleased"      );

		//bottom of keypad
		//some weridness here: since z x and c are bound in default bindings, we dont want to duplicate them here
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_A,  0, false), "AKeyPressed"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0, false), "SKeyPressed"          );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_D,    0, false), "DKeyPressed"       );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_F,  0, false), "FKeyPressed"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_V,     0, false), "VKeyPressed"      );

		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_A,  0, true), "AKeyReleased"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0, true), "SKeyReleased"          );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_D,    0, true), "DKeyReleased"       );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_F,  0, true), "FKeyReleased"         );
		map.put(KeyStroke.getKeyStroke(KeyEvent.VK_V,     0, true), "VKeyReleased"      );


		ActionMap actions = panel.getActionMap();

		//default binds
		actions.put("LeftKeyPressed",    new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_L);  } });
		actions.put("RightKeyPressed",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_R);  } });
		actions.put("UpKeyPressed",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_U);  } });
		actions.put("DownKeyPressed",    new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_D);  } });
		actions.put("BKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_B);  } });
		actions.put("ZKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_Z);  } });
		actions.put("XKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_X);  } });
		actions.put("CKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_C);  } });
		actions.put("LeftKeyReleased",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_L); } });
		actions.put("RightKeyReleased",  new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_R); } });
		actions.put("UpKeyReleased",     new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_U); } });
		actions.put("DownKeyReleased",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_D); } });
		actions.put("BKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_B); } });
		actions.put("ZKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_Z); } });
		actions.put("XKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_X); } });
		actions.put("CKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_C); } });


		//top of keypad
		actions.put("1KeyPressed",    new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_1);  } });
		actions.put("2KeyPressed",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_2);  } });
		actions.put("3KeyPressed",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_3);  } });
		actions.put("4KeyPressed",    new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_4);  } });
		actions.put("QKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_Q);  } });
		actions.put("WKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_W);  } });
		actions.put("EKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_E);  } });
		actions.put("RKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_R1);  } });
		actions.put("1KeyReleased",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_1); } });
		actions.put("2KeyReleased",  new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_2); } });
		actions.put("3KeyReleased",     new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_3); } });
		actions.put("4KeyReleased",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_4); } });
		actions.put("QKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_Q); } });
		actions.put("WKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_W); } });
		actions.put("EKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_E); } });
		actions.put("RKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_R1); } });

		//bottom of keypad
		actions.put("AKeyPressed",    new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_A);  } });
		actions.put("SKeyPressed",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_S);  } });
		actions.put("DKeyPressed",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_D1);  } });
		actions.put("FKeyPressed",    new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_F);  } });
		actions.put("VKeyPressed",       new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState | KEY_V);  } });
		actions.put("AKeyReleased",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_A); } });
		actions.put("SKeyReleased",  new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_S); } });
		actions.put("DKeyReleased",     new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_D1); } });
		actions.put("FKeyReleased",   new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_F); } });
		actions.put("VKeyReleased",      new AbstractAction() {
			public void actionPerformed(ActionEvent e) { changeKeyState(keyState & ~KEY_V); } });


		return panel;
	}

	@Override
	protected void initializePostGUI() {
		connectButton.addActionListener((e) -> {
			displayPanel.repaint();
		});

		JDialog dialog = (JDialog)this.theWindow;

		if(dialog != null) {
			dialog.setResizable(false);
		}
	}

	private void changeKeyState(int newState)
	{
		keyState = newState;

		if(!this.isBeingUsedAsAMarsTool || connectButton.isConnected())
		{
			try
			{
				Globals.memory.setRawWord(KEY_STATE, newState);
			}
			catch(AddressErrorException aee)
			{
				System.out.println("Tool author specified incorrect MMIO address!" + aee);
				System.exit(0);
			}
		}
	}

	protected void processMIPSUpdate(Observable memory, AccessNotice accessNotice)
	{
		MemoryAccessNotice notice = (MemoryAccessNotice) accessNotice;

		if(notice.getAccessType() == AccessNotice.WRITE && notice.getAddress() == SCREEN_UPDATE)
		{
			displayPanel.shouldClear = notice.getValue() != 0;
			shouldRedraw = true;

			// Copy values from memory to internal buffer, reset if we must.
			try
			{
				// Ensure block for destination exists
				Globals.memory.setRawWord(LED_BUFFER_START, 0x0);
				Globals.memory.setRawWord(LED_BUFFER_END - 0x4, 0x0);
				Globals.memory.copyMMIOFast(LED_START, LED_BUFFER_START, LED_END - LED_START);
			}
			catch(AddressErrorException aee)
			{
				System.out.println("Tool author specified incorrect MMIO address!" + aee);
				System.exit(0);
			}

			if(displayPanel.shouldClear)
			{
				displayPanel.shouldClear = false;
				resetGraphicsMemory();
			}
		}
	}

	protected void reset()
	{
		resetGraphicsMemory();
		shouldRedraw = true;
		updateDisplay();
	}

	protected JComponent getHelpComponent()
	{
		final String helpContent = "LED Display Simulator "
			+ version
			+ "\n\n"
			+ "Use this program to simulate Memory-Mapped I/O (MMIO) for a LED display output "
			+ "device. It may be run either from MARS' Tools menu or as a stand-alone application. "
			+ "For the latter, simply write a driver to instantiate a "
			+ this.getClass().getName() + " object "
			+ "and invoke its go() method.\n" + "\n"
			+ "The arrow keys can control movement.\n"
			+ "\n"
			/* + "Contact " + author + " with questions or comments.\n" */;
		JButton help = new JButton("Help");
		help.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				JTextArea ja = new JTextArea(helpContent);
				ja.setRows(10);
				ja.setColumns(40);
				ja.setLineWrap(true);
				ja.setWrapStyleWord(true);
				JOptionPane.showMessageDialog(theWindow, new JScrollPane(ja),
											  "Simulating the LED Display",
											  JOptionPane.INFORMATION_MESSAGE);
			}
		});
		return help;
	}

	private boolean shouldRedraw = true;

	protected void updateDisplay()
	{
		if(shouldRedraw)
		{
			shouldRedraw = false;
			displayPanel.repaint();
		}
	}

	static final Color[] PixelColors = new Color[]
	{
		new Color(0, 0, 0),       // black
		new Color(255, 0, 0),     // red
		new Color(255, 127, 0),   // orange
		new Color(255, 255, 0),   // yellow
		new Color(0, 255, 0),     // green
		new Color(51, 102, 255),  // blue
		new Color(255, 0, 255),   // magenta
		new Color(255, 255, 255), // white

		// extended colors!
		new Color(63, 63, 63),    // dark grey
		new Color(127, 0, 0),     // brick
		new Color(127, 63, 0),    // brown
		new Color(192, 142, 91),  // tan
		new Color(0, 127, 0),     // dark green
		new Color(25, 50, 127),   // dark blue
		new Color(63, 0, 127),    // purple
		new Color(127, 127, 127), // light grey
	};

	private class LEDDisplayPanel extends JPanel
	{
		private static final int CELL_DEFAULT_SIZE = 8;
		private static final int CELL_ZOOMED_SIZE = 12;
		private static final int COLOR_MASK = 15;

		private int cellSize = CELL_DEFAULT_SIZE;
		private int cellPadding = 0;
		private int pixelSize;
		private int displayWidth;
		private int displayHeight;

		public boolean shouldClear = false;
		private boolean drawGridLines = false;
		private boolean zoomed = false;

		public LEDDisplayPanel() {
			this.recalcSizes();
		}

		private void recalcSizes() {
			cellSize = cellSize = (zoomed ? CELL_ZOOMED_SIZE : CELL_DEFAULT_SIZE);
			cellPadding = drawGridLines ? 1 : 0;
			pixelSize = cellSize - cellPadding;
			pixelSize = cellSize - cellPadding;
			displayWidth = (N_COLUMNS * cellSize);
			displayHeight = (N_ROWS * cellSize);
			this.setPreferredSize(new Dimension(displayWidth, displayHeight));
		}

		public void setGridLinesEnabled(boolean e) {
			if(e != drawGridLines) {
				drawGridLines = e;
				this.recalcSizes();
			}
		}

		public void setZoomed(boolean e) {
			if(e != zoomed) {
				zoomed = e;
				this.recalcSizes();
			}
		}

		public void paintComponent(Graphics g)
		{
			if(!connectButton.isConnected()) {
				g.setColor(Color.BLACK);
				g.fillRect(0, 0, displayWidth, displayHeight);
				g.setColor(Color.RED);
				g.setFont(new Font("Sans-Serif", Font.BOLD, 24));
				g.drawString("vvvv CLICK THE CONNECT BUTTON!", 10, displayHeight - 10);
				return;
			}

			if(drawGridLines) {
				g.setColor(Color.GRAY);
				g.fillRect(0, 0, displayWidth, displayHeight);
			}

			int ptr = LED_BUFFER_START;

			try
			{
				for(int row = 0; row < N_ROWS; row++)
				{
					int y = row * cellSize;

					for(int col = 0, x = 0; col < N_COLUMNS; col += 4, ptr += 4)
					{
						int pixel = Globals.memory.getWordNoNotify(ptr);

						g.setColor(PixelColors[pixel & COLOR_MASK]);
						g.fillRect(x, y, pixelSize, pixelSize);
						x += cellSize;
						g.setColor(PixelColors[(pixel >> 8) & COLOR_MASK]);
						g.fillRect(x, y, pixelSize, pixelSize);
						x += cellSize;
						g.setColor(PixelColors[(pixel >> 16) & COLOR_MASK]);
						g.fillRect(x, y, pixelSize, pixelSize);
						x += cellSize;
						g.setColor(PixelColors[(pixel >> 24) & COLOR_MASK]);
						g.fillRect(x, y, pixelSize, pixelSize);
						x += cellSize;
					}
				}
			}
			catch(AddressErrorException aee)
			{
				System.out.println("Tool author specified incorrect MMIO address!" + aee);
				System.exit(0);
			}
		}
	}

	private static void resetGraphicsMemory()
	{
		try
		{
			Globals.memory.zeroMMIOFast(LED_START, LED_END - LED_START);
		}
		catch(AddressErrorException aee)
		{
			System.out.println("Tool author specified incorrect MMIO address!" + aee);
			System.exit(0);
		}
	}
}
