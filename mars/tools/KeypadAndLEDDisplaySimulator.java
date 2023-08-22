package mars.tools;

import javax.swing.*;
import javax.swing.plaf.basic.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;

import mars.Globals;
import mars.mips.hardware.*;
import mars.simulator.Exceptions;

/*
 Copyright (c) 2009 Jose Baiocchi, 2016-2023 Jarrett Billingsley

 Developed by Jose Baiocchi (baiocchi@cs.pitt.edu)
 Modified and greatly extended by Jarrett Billingsley (jarrett@cs.pitt.edu)

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
 * @author Jarrett Billingsley
 * @version 1.1. 16 February 2010.
 */
public class KeypadAndLEDDisplaySimulator extends AbstractMarsToolAndApplication {
	/*
	Classic mode memory map
	=======================

	0xFFFF0000: DISPLAY_CTRL.w (WO)
	0xFFFF0004: DISPLAY_KEYS.w (RO)
	0xFFFF0008: DISPLAY_BASE.b (WO) - start of user-written LED area
	0xFFFF1007: DISPLAY_END.b-1 (write-only) - end of user-written LED area

	The old implementation had a "secret" buffer from 0xFFFF2008 to 0xFFFF3007 which was
	*technically* accessible by user programs, but no correctly-written program would ever
	do that. The new implementation has no such buffer anymore, so writing to this region
	has no effect.

	Enhanced mode memory map
	========================

	MMIO Page  0: global, tilemap control, input, and palette RAM
	MMIO Pages 1-4: framebuffer data
	MMIO Page  5: tilemap table and sprite table
	MMIO Pages 6-9: tilemap graphics
	MMIO Pages A-D: sprite graphics
	MMIO Pages E-F: unused right now

	GLOBAL REGISTERS:

		0xFFFF0000: DISPLAY_CTRL.w           (WO)
			low 2 bits are mode:
				00: undefined
				01: framebuffer on
				10: tilemap on
				11: framebuffer and tilemap on

			bit 8 has no specific meaning but setting it along with mode switches to enhanced mode

			bits 16-23 are the milliseconds per frame used by DISPLAY_SYNC, but limited to
			the range [10, 100].

			bits 9-15 and 24-31 are undefined atm

			so set DISPLAY_CTRL to:
				(ms_per_frame << 16) | 0x100 | mode

		0xFFFF0004: DISPLAY_ORDER.w          (WO, order in which tilemap and framebuffer should
			be composited - 0 = tilemap in front of framebuffer, 1 = tilemap behind framebuffer)
		0xFFFF0008: DISPLAY_SYNC.w           (RW)
			write to indicate frame is over and ready for display (value is ignored)
			read to wait for next frame (always reads 0)
		0xFFFF000C: DISPLAY_FB_CLEAR.w       (WO, clears framebuffer to color 0 when written)
		0xFFFF0010: DISPLAY_PALETTE_RESET.w  (WO, resets palette to default values when written)

	TILEMAP REGISTERS:

		0xFFFF0020: DISPLAY_TM_SCX.w         (WO, tilemap X scroll position)
		0xFFFF0024: DISPLAY_TM_SCY.w         (WO, tilemap Y scroll position)

		-- blank --

	INPUT REGISTERS:

		0xFFFF0040: DISPLAY_KEY_HELD.w       (write to choose key, read to get state)
		0xFFFF0044: DISPLAY_KEY_PRESSED.w    (write to choose key, read to get state)
		0xFFFF0048: DISPLAY_KEY_RELEASED.w   (write to choose key, read to get state)
		0xFFFF004C: DISPLAY_MOUSE_X.w        (RO, X position of mouse or -1 if mouse not over)
		0xFFFF0050: DISPLAY_MOUSE_Y.w        (RO, Y position of mouse or -1 if mouse not over)
		0xFFFF0054: DISPLAY_MOUSE_HELD.w     (RO, bitflags of mouse buttons held)
		0xFFFF0058: DISPLAY_MOUSE_PRESSED.w  (RO, bitflags of mouse buttons pressed, incl wheel)
		0xFFFF005C: DISPLAY_MOUSE_RELEASED.w (RO, bitflags of mouse buttons released)

		-- blank --

	PALETTE RAM:

		0xFFFF0C00-0xFFFF0FFF: 256 4B palette entries, byte order [BB, GG, RR, 00]
			(0x00RRGGBB in register becomes that in memory)

	FRAMEBUFFER DATA:

		0xFFFF1000-0xFFFF4FFF: 128x128 (16,384) 1B pixels, each is an index into the palette

	TILEMAP AND SPRITE TABLES:

		0xFFFF5000-0xFFFF57FF: 32x32 2B tilemap entries consisting of (tile, flags)
			tile graphics are fetched from (0xFFFF6000 + tile * 64)
			flags is xxxxHVP
				H = horizontal flip
				V = vertical flip
				P = priority (appears over sprites)

		0xFFFF5800-0xFFFF5FFF: 256 4B sprite entries consisting of (X, Y, tile, flags)
			X and Y are signed
			tile graphics are fetched from (0xFFFFA000 + tile * 64)
			flags is PPPPSHVE
				E = enable (1 for visible)
				V = vertical flip
				H = horizontal flip
				S = size (0 = 8x8 (1 tile), 1 = 16x16 (4 tiles))
					for 16x16 sprites, tiles are put in order left-to-right, top-to-bottom, like:
						1 2
						3 4
				PPPP = palette row index
					this * 16 is added to all color indexes in sprite graphics when drawn, so that
					you can reuse the same sprite graphics with multiple palettes without having
					to update the palette RAM

	GRAPHICS DATA:
		0xFFFF6000-0xFFFF9FFF: 256 8x8 1Bpp indexed color tilemap tiles
		0xFFFFA000-0xFFFFDFFF: 256 8x8 1Bpp indexed color sprite tiles

	-- conspicuous blank space from 0xFFFFE000-0xFFFFFFFF that could be used for sound --

	Modes and how to switch
	=======================

		MODE 0: Classic mode

			It starts in mode 0, classic mode. This mode provides a 64x64-pixel linear
			framebuffer, 1 byte per pixel, with a fixed 16-color palette.

			A simple kind of double buffering is used to reduce the likelihood of tearing.
			The back buffer is readable and writable by the user and exists in memory
			in the address range [DISPLAY_BASE .. DISPLAY_END). The front buffer is
			*technically* writable by the user but well-behaved users would never do this.

			Writing 0 to DISPLAY_CTRL copies the back buffer into the front buffer.

			Writing a 1 to DISPLAY_CTRL copies the back buffer into the front buffer,
			then clears the back buffer to all 0 (black).

			Input is limited to the keyboard arrow keys and Z, X, C, B keys. Input is
			retrieved by reading from DISPLAY_KEYS, which returns the pressed state of
			each key as bitflags.

			As long as only the values 0 and 1 are written to DISPLAY_CTRL, it will stay
			in mode 0.

		MODE 1: Enhanced framebuffer

			Writing a value > 256 (0x100) to DISPLAY_CTRL will switch into enhanced mode.
			The mode number is the value written to (DISPLAY_CTRL & 3). If DISPLAY_CTRL & 3
			is 0 and the value is > 256, the results are undefined. Don't do that.

			All enhanced modes use a 128x128 display.

			Mode 1 is similar to mode 0, but with higher capabilities. This mode provides
			a linear framebuffer, 1 byte per pixel, with a user-definable 256-entry palette.

			Palette entries are RGB888, padded to 4 bytes. The palette is initialized in
			some way so that the palette index can be interpreted as RGB222 or RGB232 or
			something so that you can get to drawing stuff to the screen right away.

			The framebuffer is 128x128 pixels, the same size as the display. This already
			takes up 16KB of the tight 64KB of MMIO space, so that's all you get.

		MODE 2: Tilemap

			Mode 2 is totally different. In this mode, the tilemap is used instead.
			The tilemap is a 32x32-tile grid, where each tile is 8x8 pixels, for a total
			of 256x256 pixels (4 full screens).

			Each tile in the tilemap can be one of 256 tile graphics. There is enough
			space for all 256 tile indexes to have their own 8x8 1Bpp images.

			Each tile in the tilemap can also be flipped horizontally and/or vertically,
			or set to "priority" so that it appears in front of sprites. (Sprites are
			explained later.)

			The tilemap can be scrolled freely at pixel resolution on both axes. The scroll
			amount can be written as a signed integer but will be ANDed with 255. The tilemap
			wraps around at the edges.

		MODE 3: Both

			Mode 3 displays the framebuffer and the tilemap, with the tilemap in front of
			the framebuffer.

	Palette and Transparency
	========================

		There is a single global 256-entry palette. Each palette entry is 4 bytes, and is an
		RGB888 color with 1 byte unused. The tilemap and the sprites share the palette.

		Palette entry 0 is special as it specifies the background color. In tilemap and sprite
		graphics, a color index of 0 means "transparent," so this color will not appear in those
		graphics.

	Background Color
	================

		Palette entry 0 is the global background color to which the display is cleared before
		drawing anything else.

		If the framebuffer is visible, writing any value to DISPLAY_FB_CLEAR will fill the
		entire framebuffer with color index 0.

		If the framebuffer is not visible but the tilemap is, the background color will appear
		behind transparent pixels in tiles.

	Sprites
	=======

		Sprites are available in any enhanced mode.

		There can be up to 256 sprites onscreen. Each sprite can be either 8x8 pixels (1 tile) or
		16x16 pixels (4 tiles). Each sprite can be positioned anywhere onscreen including off all
		four sides. Each sprite can also be flipped horizontally or vertically.

		Sprite priority is by order in the list. Sprite 0 appears on top of sprite 1, which
		appears on top of sprite 2, etc.

		There are no "per-scanline limits" on the number of visible sprites.

		Sprite graphics are specified as 8x8 tiles just like the tilemap. For 16x16 pixel sprites,
		the four tiles for the sprite are assumed to be contiguous in memory, and are drawn
		in "reading order" (left-to-right, then top-to-bottom). If tiles 1, 2, 3, 4 are contiguous
		in memory, then they are drawn in this arrangement:
			1 2
			3 4

	Graphics Data
	=============

		The tilemap and the sprites each have their own independent graphics data areas.

		The graphics are 8x8-pixel tiles, where each pixel is 1 byte, for a total of 64 bytes
		per tile. The pixels are stored in "reading order". Each pixel is an index into the
		global palette.

	Screen Compositing
	==================

		If DISPLAY_ORDER is 0 (the default), the display elements are drawn from back (first drawn)
		to front (last drawn) like so:

			- Background color
			- Framebuffer
			- Tilemap tiles without priority
			- Sprites, from sprite 255 down to sprite 0
			- Tilemap tiles with priority

		If DISPLAY_ORDER is set to 1, the framebuffer is instead drawn last, on top of
		everything else.

	*/

	// --------------------------------------------------------------------------------------------
	// Constants

	static final long serialVersionUID = 1; // To eliminate a warning about serializability.

	private static final String version = "Version 2";
	private static final String title = "Keypad and LED Display MMIO Simulator";
	private static final String heading = "Classic Mode";
	private static final String enhancedHeading = "Enhanced Mode";
	private static final int DISPLAY_CTRL = Memory.memoryMapBaseAddress;
	private static final int ENHANCED_MODE_SWITCH_VALUE = 257; // 0x101

	// --------------------------------------------------------------------------------------------
	// Instance fields

	private JPanel panel;
	private JCheckBox gridCheckBox;
	private JCheckBox zoomCheckBox;
	private LEDDisplayPanel displayPanel;
	private ClassicLEDDisplayPanel classicDisplay;
	private EnhancedLEDDisplayPanel enhancedDisplay;
	private boolean isEnhanced = false;

	// --------------------------------------------------------------------------------------------
	// Standalone main

	public static void main(String[] args) {
		new KeypadAndLEDDisplaySimulator(title + " stand-alone, " + version, heading).go();
	}

	// --------------------------------------------------------------------------------------------
	// AbstractMarsToolAndApplication implementation

	public KeypadAndLEDDisplaySimulator(String title, String heading) {
		super(title, heading);
	}

	public KeypadAndLEDDisplaySimulator() {
		super(title + ", " + version, heading);
	}

	@Override
	public String getName() {
		return "Keypad and LED Display Simulator";
	}

	/** Builds the actual GUI for the tool. */
	@Override
	protected JComponent buildMainDisplayArea() {
		panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		classicDisplay = new ClassicLEDDisplayPanel(this);
		enhancedDisplay = new EnhancedLEDDisplayPanel(this);

		displayPanel = classicDisplay;

		JPanel subPanel = new JPanel();
		gridCheckBox = new JCheckBox("Show Grid Lines");
		gridCheckBox.addItemListener((e) -> {
			displayPanel.setGridLinesEnabled(e.getStateChange() == ItemEvent.SELECTED);
			displayPanel.revalidate();
			this.theWindow.pack();
			displayPanel.repaint();
		});
		subPanel.add(gridCheckBox);

		zoomCheckBox = new JCheckBox("Zoom");
		zoomCheckBox.addItemListener((e) -> {
			displayPanel.setZoomed(e.getStateChange() == ItemEvent.SELECTED);
			displayPanel.revalidate();
			this.theWindow.pack();
			displayPanel.repaint();
		});
		subPanel.add(zoomCheckBox);

		panel.add(subPanel);
		panel.add(displayPanel);

		displayPanel.requestFocusInWindow();

		return panel;
	}

	/** Called after the GUI has been constructed. */
	@Override
	protected void initializePostGUI() {
		// force a repaint when the connect button is clicked
		connectButton.addActionListener((e) -> {
			displayPanel.repaint();
		});

		// no resizable!
		JDialog dialog = (JDialog)this.theWindow;

		if(dialog != null) {
			dialog.setResizable(false);
		}

		// make it so if the window gets focus, focus the display, so it can get events
		theWindow.addWindowFocusListener(new WindowAdapter() {
			public void windowGainedFocus(WindowEvent e) {
				displayPanel.requestFocusInWindow();
			}
		});

		/*

		System.out.println("double-buffered? " + theWindow.isDoubleBuffered());

		// must call this so the call to createBufferStrategy succeeds
		theWindow.pack();

		// set up for double-buffering
		try {
			theWindow.createBufferStrategy(2);
		} catch(Exception e) {
			System.err.println("ERROR: couldn't set up double-buffering: " + e);
		}

		var strategy = theWindow.getBufferStrategy();
		System.out.println("Strategy: " + strategy);
		System.out.println("double-buffered? " + theWindow.isDoubleBuffered());
		var caps = strategy.getCapabilities();
		System.out.println("BB: " + caps.getBackBufferCapabilities().isTrueVolatile());
		System.out.println("FB: " + caps.getFrontBufferCapabilities().isTrueVolatile());
		System.out.println("FC: " + caps.getFlipContents());
		*/

		// TODO: experiment with driving painting from a separate thread instead of
		// relying on repaint events
	}

	/** Called when the Connect button is clicked, to hook it into the memory subsystem. */
	@Override
	protected void addAsObserver() {
		// end address has to be the address of the last *word* observed
		int endAddress = Memory.memoryMapLimitAddress - Memory.WORD_LENGTH_BYTES + 1;
		addAsObserver(Memory.memoryMapBaseAddress, endAddress);
	}

	/** Called when the Reset button is clicked. */
	@Override
	protected void reset() {
		displayPanel.reset();
		updateDisplay();
	}

	/** Used to watch for writes to control registers. */
	@Override
	protected void processMIPSUpdate(Observable memory, AccessNotice accessNotice) {
		MemoryAccessNotice notice = (MemoryAccessNotice) accessNotice;

		if(notice.getAccessType() == AccessNotice.WRITE) {
			// Can't switch on addresses because they're dynamic based on
			// Memory.memoryMapBaseAddress.
			if(notice.getAddress() == DISPLAY_CTRL) {
				int value = notice.getValue();

				if(value >= ENHANCED_MODE_SWITCH_VALUE) {
					this.switchToEnhancedMode();
				}

				this.displayPanel.writeToCtrl(value);
			} else {
				this.displayPanel.handleWrite(
					notice.getAddress(), notice.getLength(), notice.getValue());
			}
		} else {
			// reads...
		}
	}

	/** Called any time an MMIO access is made. */
	@Override
	protected void updateDisplay() {
		displayPanel.repaintIfNeeded();
	}

	// --------------------------------------------------------------------------------------------
	// Mode switching

	private void switchToEnhancedMode() {
		if(!isEnhanced) {
			isEnhanced = true;

			panel.remove(classicDisplay);
			panel.add(enhancedDisplay);
			displayPanel = enhancedDisplay;

			gridCheckBox.setSelected(false);
			gridCheckBox.setEnabled(false);
			displayPanel.setZoomed(zoomCheckBox.isSelected());

			if(this.isBeingUsedAsAMarsTool)
				headingLabel.setText(enhancedHeading);

			displayPanel.revalidate();
			this.theWindow.pack();
			displayPanel.repaint();
		}
	}

	// --------------------------------------------------------------------------------------------
	// Common base class for both kinds of displays

	private static abstract class LEDDisplayPanel extends JPanel {
		protected KeypadAndLEDDisplaySimulator sim;

		protected Font bigFont = new Font("Sans-Serif", Font.BOLD, 24);

		protected boolean haveFocus = false;
		protected boolean shouldRepaint = true;
		protected boolean drawGridLines = false;
		protected boolean zoomed = false;

		protected final int nColumns;
		protected final int nRows;
		protected final int cellDefaultSize;
		protected final int cellZoomedSize;
		protected int cellSize;
		protected int cellPadding = 0;
		protected int pixelSize;
		protected int displayWidth;
		protected int displayHeight;

		public LEDDisplayPanel(KeypadAndLEDDisplaySimulator sim,
			int nColumns, int nRows, int cellDefaultSize, int cellZoomedSize) {
			this.sim = sim;
			this.setFocusable(true);
			this.setFocusTraversalKeysEnabled(false);
			this.addMouseListener(new MouseAdapter() {
				public void mouseClicked(MouseEvent e) {
					if(!haveFocus) {
						requestFocusInWindow();
					}
				}
			});

			this.addFocusListener(new FocusListener() {
				public void focusGained(FocusEvent e) {
					haveFocus = true;
					repaint();
				}

				public void focusLost(FocusEvent e) {
					haveFocus = false;
					repaint();
				}
			});

			this.nColumns = nColumns;
			this.nRows = nRows;
			this.cellDefaultSize = cellDefaultSize;
			this.cellZoomedSize = cellZoomedSize;
			this.recalcSizes();
		}

		protected void recalcSizes() {
			cellSize      = zoomed ? cellZoomedSize : cellDefaultSize;
			cellPadding   = drawGridLines ? 1 : 0;
			pixelSize     = cellSize - cellPadding;
			pixelSize     = cellSize - cellPadding;
			displayWidth  = (nColumns * cellSize);
			displayHeight = (nRows * cellSize);
			this.setPreferredSize(new Dimension(displayWidth, displayHeight));
		}

		public void setGridLinesEnabled(boolean e) {
			if(e != this.drawGridLines) {
				this.drawGridLines = e;
				this.recalcSizes();
			}
		}

		public void setZoomed(boolean e) {
			if(e != this.zoomed) {
				this.zoomed = e;
				this.recalcSizes();
			}
		}

		public void setShouldRepaint(boolean b) {
			this.shouldRepaint = b;
		}

		public void repaintIfNeeded() {
			if(this.shouldRepaint) {
				this.shouldRepaint = false;
				this.repaint();
			}
		}

		@Override
		public void paintComponent(Graphics g) {
			if(!sim.connectButton.isConnected()) {
				g.setColor(Color.BLACK);
				g.fillRect(0, 0, displayWidth, displayHeight);
				g.setColor(Color.RED);
				g.setFont(bigFont);
				g.drawString("vvvv CLICK THE CONNECT BUTTON!", 10, displayHeight - 10);
			} else {
				this.paintDisplay(g);

				if(!haveFocus) {
					g.setColor(new Color(0, 0, 0, 127));
					g.fillRect(0, 0, displayWidth, displayHeight);
					g.setColor(Color.YELLOW);
					g.setFont(bigFont);
					g.drawString("Click here to interact.", 10, displayHeight / 2);
				}
			}
		}

		public abstract void reset();
		public abstract void writeToCtrl(int value);
		public abstract void handleWrite(int addr, int length, int value);
		protected abstract void paintDisplay(Graphics g);
	}

	// --------------------------------------------------------------------------------------------
	// Classic display

	/** The classic 64x64 graphical display with 8-key input. */
	private static class ClassicLEDDisplayPanel extends LEDDisplayPanel {
		private static final int N_COLUMNS = 64;
		private static final int N_ROWS = 64;
		private static final int CELL_DEFAULT_SIZE = 8;
		private static final int CELL_ZOOMED_SIZE = 12;

		private static final int KEY_U = 1;
		private static final int KEY_D = 2;
		private static final int KEY_L = 4;
		private static final int KEY_R = 8;
		private static final int KEY_B = 16;
		private static final int KEY_Z = 32;
		private static final int KEY_X = 64;
		private static final int KEY_C = 128;

		private static final int DISPLAY_KEYS = DISPLAY_CTRL + Memory.WORD_LENGTH_BYTES;
		private static final int DISPLAY_BASE = DISPLAY_KEYS + Memory.WORD_LENGTH_BYTES;
		private static final int DISPLAY_SIZE = N_ROWS * N_COLUMNS; // bytes
		private static final int DISPLAY_END = DISPLAY_BASE + DISPLAY_SIZE;

		private static final int COLOR_MASK = 15;

		/** color palette. */
		private static final int[][] PixelColors = new int[][] {
			{0, 0, 0},       // black
			{255, 0, 0},     // red
			{255, 127, 0},   // orange
			{255, 255, 0},   // yellow
			{0, 255, 0},     // green
			{51, 102, 255},  // blue
			{255, 0, 255},   // magenta
			{255, 255, 255}, // white

			// extended colors!
			{63, 63, 63},    // dark grey
			{127, 0, 0},     // brick
			{127, 63, 0},    // brown
			{192, 142, 91},  // tan
			{0, 127, 0},     // dark green
			{25, 50, 127},   // dark blue
			{63, 0, 127},    // purple
			{127, 127, 127}, // light grey
		};

		private boolean doingSomethingWeird = false;
		private int keyState = 0;
		private BufferedImage image =
			new BufferedImage(N_COLUMNS, N_ROWS, BufferedImage.TYPE_INT_RGB);

		public ClassicLEDDisplayPanel(KeypadAndLEDDisplaySimulator sim) {
			super(sim, N_COLUMNS, N_ROWS, CELL_DEFAULT_SIZE, CELL_ZOOMED_SIZE);

			this.addKeyListener(new KeyListener() {
				public void keyTyped(KeyEvent e) {
				}

				public void keyPressed(KeyEvent e) {
					switch(e.getKeyCode()) {
						case KeyEvent.VK_LEFT:  changeKeyState(keyState | KEY_L); break;
						case KeyEvent.VK_RIGHT: changeKeyState(keyState | KEY_R); break;
						case KeyEvent.VK_UP:    changeKeyState(keyState | KEY_U); break;
						case KeyEvent.VK_DOWN:  changeKeyState(keyState | KEY_D); break;
						case KeyEvent.VK_B:     changeKeyState(keyState | KEY_B); break;
						case KeyEvent.VK_Z:     changeKeyState(keyState | KEY_Z); break;
						case KeyEvent.VK_X:     changeKeyState(keyState | KEY_X); break;
						case KeyEvent.VK_C:     changeKeyState(keyState | KEY_C); break;
						default: break;
					}
				}

				public void keyReleased(KeyEvent e) {
					switch(e.getKeyCode()) {
						case KeyEvent.VK_LEFT:  changeKeyState(keyState & ~KEY_L); break;
						case KeyEvent.VK_RIGHT: changeKeyState(keyState & ~KEY_R); break;
						case KeyEvent.VK_UP:    changeKeyState(keyState & ~KEY_U); break;
						case KeyEvent.VK_DOWN:  changeKeyState(keyState & ~KEY_D); break;
						case KeyEvent.VK_B:     changeKeyState(keyState & ~KEY_B); break;
						case KeyEvent.VK_Z:     changeKeyState(keyState & ~KEY_Z); break;
						case KeyEvent.VK_X:     changeKeyState(keyState & ~KEY_X); break;
						case KeyEvent.VK_C:     changeKeyState(keyState & ~KEY_C); break;
						default: break;
					}
				}
			});
		}

		/** set the key state to the new state, and update the value in MIPS memory
		for the program to be able to read. */
		private void changeKeyState(int newState) {
			keyState = newState;

			if(!sim.isBeingUsedAsAMarsTool || sim.connectButton.isConnected()) {
				synchronized(Globals.memoryAndRegistersLock) {
					// 1 is (DISPLAY_KEYS - MMIO Base) / 4
					Globals.memory.getMMIOPage(0)[1] = newState;
				}
			}
		}

		/** quickly clears the graphics memory to 0 (black). */
		@Override
		public void reset() {
			this.resetGraphicsMemory(true);
			this.setShouldRepaint(true);
		}

		@Override
		public void writeToCtrl(int value) {
			// Copy values from memory to internal buffer, reset if we must.
			this.updateImage();

			if(value != 0)
				this.resetGraphicsMemory(false);

			this.setShouldRepaint(true);
		}

		@Override
		public void handleWrite(int addr, int length, int value) {
			int offset = addr - Memory.memoryMapBaseAddress;

			if(offset > 0x1007) {
				doingSomethingWeird = true;
			}
		}

		private void resetGraphicsMemory(boolean clearBuffer) {
			synchronized(Globals.memoryAndRegistersLock) {
				// I hate using magic values like this but: these are the addresses of
				// DISPLAY_BASE and DISPLAY_END, minus the MMIO base address, divided
				// by 4 since the array indexes are words, not bytes.
				Arrays.fill(Globals.memory.getMMIOPage(0), 2, 1024, 0);
				Arrays.fill(Globals.memory.getMMIOPage(1), 0,    2, 0);

				if(clearBuffer) {
					this.clearImage();
				}
			}
		}

		// grab values out of RAM and turn them into image pixels
		private void updateImage() {
			synchronized(image) {
				synchronized(Globals.memoryAndRegistersLock) {
					int[] page = Globals.memory.getMMIOPage(0);
					int ptr = 2;
					var r = image.getRaster();

					for(int row = 0; row < N_ROWS; row++) {
						for(int col = 0; col < N_COLUMNS; col += 4, ptr++) {
							// hacky, but.
							if(ptr == 1024) {
								ptr = 0;
								page = Globals.memory.getMMIOPage(1);
							}

							int pixel = page[ptr];

							r.setPixel(col, row, PixelColors[pixel & COLOR_MASK]);
							r.setPixel(col + 1, row, PixelColors[(pixel >> 8) & COLOR_MASK]);
							r.setPixel(col + 2, row, PixelColors[(pixel >> 16) & COLOR_MASK]);
							r.setPixel(col + 3, row, PixelColors[(pixel >> 24) & COLOR_MASK]);
						}
					}
				}
			}
		}

		// Clear the image to black
		private void clearImage() {
			var g = image.createGraphics();
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.dispose();
		}

		@Override
		protected void paintDisplay(Graphics g) {
			synchronized(image) {
				g.drawImage(image, 0, 0, displayWidth, displayHeight, null);
			}

			if(drawGridLines) {
				g.setColor(Color.GRAY);

				for(int col = 0; col < N_COLUMNS; col++) {
					int x = col * cellSize;
					g.drawLine(x, 0, x, displayHeight);
				}

				for(int row = 0; row < N_ROWS; row++) {
					int y = row * cellSize;
					g.drawLine(0, y, displayWidth, y);
				}
			}

			if(doingSomethingWeird) {
				g.setColor(new Color(0, 0, 0, 127));
				g.fillRect(0, 0, displayWidth, displayHeight);
				g.setColor(Color.YELLOW);
				g.setFont(bigFont);
				g.drawString("This window needs to be open and", 10, 40);
				g.drawString("connected before you run your", 10, 70);
				g.drawString("program. Stop the program, hit", 10, 100);
				g.drawString("assemble, and hit run.", 10, 130);
			}
		}
	}

	// --------------------------------------------------------------------------------------------
	// Enhanced display

	/** The new, enhanced display. */
	private static class EnhancedLEDDisplayPanel extends LEDDisplayPanel {
		// Mode bits
		private static final int MODE_FB_ENABLE = 1;
		private static final int MODE_TM_ENABLE = 2;
		private static final int MODE_MASK = 3;

		// Framerate
		private static final int MS_PER_FRAME_SHIFT = 16;
		private static final int MS_PER_FRAME_MASK = 0xFF;
		private static final int MIN_MS_PER_FRAME = 10;
		private static final int MAX_MS_PER_FRAME = 100;

		// Display and framebuffer size
		private static final int N_COLUMNS = 128;
		private static final int N_ROWS = 128;
		private static final int CELL_DEFAULT_SIZE = 4;
		private static final int CELL_ZOOMED_SIZE = 6;

		// Tilemap constants
		private static final int N_TM_COLUMNS = 32;
		private static final int N_TM_ROWS = 32;

		// Tilemap/sprite flag constants
		private static final int PRIORITY = 1; // for tiles
		private static final int ENABLE = 1; // for sprites
		private static final int VFLIP = 2; // tiles + sprites
		private static final int HFLIP = 4; // tiles + sprites

		// DISPLAY_CTRL
		private int msPerFrame = 16;
		private boolean fbEnabled = false;
		private boolean tmEnabled = false;

		// DISPLAY_ORDER
		private boolean fbInFront = false;

		// DISPLAY_TM_SCX/SCY
		private int tmScx = 0;
		private int tmScy = 0;

		// Palette RAM (ints, because setPixel expects ints)
		private int[][] paletteRam = new int[256][4];

		// Shadow for the background color entry - paletteRam[0] is set to transparent
		private int[] bgColor = { 0, 0, 0, 255 };

		// Framebuffer RAM
		private byte[] fbRam = new byte[N_COLUMNS * N_ROWS];

		// Tilemap table and graphics
		private byte[] tmTable = new byte[N_TM_COLUMNS * N_TM_ROWS * 2];
		private byte[] tmGraphics = new byte[256 * 8 * 8];

		// Sprite table and graphics
		private byte[] sprTable = new byte[256 * 4];
		private byte[] sprGraphics = new byte[256 * 8 * 8];

		// Dirty flags (set to true if things are changed, forcing a redraw of those layers)
		private boolean isPalDirty = true;
		private boolean isFbDirty = true;
		private boolean isTmDirty = true;
		private boolean isSprDirty = true;

		// Compositing layers
		private BufferedImage fbLayer =
			new BufferedImage(N_COLUMNS, N_ROWS, BufferedImage.TYPE_INT_ARGB);
		private BufferedImage tmLayerLo =
			new BufferedImage(N_COLUMNS, N_ROWS, BufferedImage.TYPE_INT_ARGB);
		private BufferedImage tmLayerHi =
			new BufferedImage(N_COLUMNS, N_ROWS, BufferedImage.TYPE_INT_ARGB);
		private BufferedImage spriteLayer =
			new BufferedImage(N_COLUMNS, N_ROWS, BufferedImage.TYPE_INT_ARGB);
		private BufferedImage finalImage =
			new BufferedImage(N_COLUMNS, N_ROWS, BufferedImage.TYPE_INT_ARGB);

		// ----------------------------------------------------------------------------------------
		// Constructor

		public EnhancedLEDDisplayPanel(KeypadAndLEDDisplaySimulator sim) {
			super(sim, N_COLUMNS, N_ROWS, CELL_DEFAULT_SIZE, CELL_ZOOMED_SIZE);
			this.initializePaletteRam();
		}

		// ----------------------------------------------------------------------------------------
		// Base class method implementations

		@Override
		public void reset() {
			this.initializePaletteRam();
		}

		@Override
		public void writeToCtrl(int value) {
			int mode = value & MODE_MASK;

			// handle the undefined case by treating it like mode 1
			if(mode == 0)
				mode = MODE_FB_ENABLE;

			this.fbEnabled = (mode & MODE_FB_ENABLE) != 0;
			this.tmEnabled = (mode & MODE_TM_ENABLE) != 0;

			// extract ms/frame and clamp to valid range
			int msPerFrame = (value >> MS_PER_FRAME_SHIFT) & MS_PER_FRAME_MASK;
			msPerFrame = Math.min(msPerFrame, MAX_MS_PER_FRAME);
			msPerFrame = Math.max(msPerFrame, MIN_MS_PER_FRAME);

			this.msPerFrame = msPerFrame;
		}

		@Override
		public void paintDisplay(Graphics g) {
			g.drawImage(finalImage, 0, 0, displayWidth, displayHeight, null);

			/*
			if(fbEnabled)
				g.drawString("FB", 10, 60);

			if(tmEnabled)
				g.drawString("TM", 60, 60);

			g.drawString(msPerFrame + " ms/frame", 10, 90);
			*/
		}

		// big ugly thing to dispatch MMIO writes to their appropriate methods
		@Override
		public void handleWrite(int addr, int length, int value) {
			int page = (addr >> 12) & 0xF;
			int offs = addr & 0xFFF;

			switch(page) {
				// MMIO Page 0: global, tilemap control, input, and palette RAM
				case 0:
					// ignore non-word stores
					if(offs < 0x40 && length == Memory.WORD_LENGTH_BYTES) {
						switch(offs) {
							// 0xFFFF0004: DISPLAY_ORDER
							case 0x004: this.fbInFront = value != 0; break;
							// 0xFFFF0008: DISPLAY_SYNC
							case 0x008: this.compositeFrame(); break;
							// 0xFFFF000C: DISPLAY_FB_CLEAR
							case 0x00C: this.clearFb(); break;
							// 0xFFFF0010: DISPLAY_PALETTE_RESET
							case 0x010: this.initializePaletteRam(); break;
							// 0xFFFF0020: DISPLAY_TM_SCX
							case 0x020: this.tmScx = value; break;
							// 0xFFFF0024: DISPLAY_TM_SCY
							case 0x024: this.tmScy = value; break;
							default: break;
						}
					} else if(offs <= 0x48) {
						// TODO: input stuff
						// 0xFFFF0040: DISPLAY_KEY_HELD
						// 0xFFFF0044: DISPLAY_KEY_PRESSED
						// 0xFFFF0048: DISPLAY_KEY_RELEASED
					} else if(offs >= 0xC00) {
						this.writePalette(offs - 0xC00, length, value);
					}
					break;

				// MMIO Pages 1-4: framebuffer data
				case 1: case 2: case 3: case 4:
					// 0xFFFF1000-0xFFFF4FFF: 128x128 (16,384) 1B pixels
					this.writeFb((addr & 0xFFFF) - 0x1000, length, value);
					break;

				// MMIO Page 5: tilemap table and sprite table
				case 5:
					if(offs < 0x800) {
						// 0xFFFF5000-0xFFFF57FF: 32x32 2B tilemap entries
						this.writeTmTable(offs, length, value);
					} else {
						// 0xFFFF5800-0xFFFF5FFF: 256 4B sprite entries
						this.writeSprTable(offs - 0x800, length, value);
					}
					break;

				// MMIO Pages 6-9: tilemap graphics
				case 6: case 7: case 8: case 9:
					this.writeTmGfx((addr & 0xFFFF) - 0x6000, length, value);
					break;

				// MMIO Pages A-D: sprite graphics
				case 0xA: case 0xB: case 0xC: case 0xD:
					this.writeSprGfx((addr & 0xFFFF) - 0xA000, length, value);
					break;

				// MMIO Pages E-F: unused right now
				default:
					break;
			}
		}

		// TODO
		private void writeTmTable(int offs, int length, int value) { }
		// TODO
		private void writeSprTable(int offs, int length, int value) { }
		// TODO
		private void writeTmGfx(int offs, int length, int value) { }
		// TODO
		private void writeSprGfx(int offs, int length, int value) { }

		// ----------------------------------------------------------------------------------------
		// Palette methods

		private static int[] Intensities = { 0, 63, 127, 255 };

		// Initialize the palette RAM to a default palette, so you can start
		// drawing stuff right away without needing to do so from software.
		private void initializePaletteRam() {
			// entry 0 of the *array* is transparent, so that the methods for
			// drawing pixels don't have to special-case.
			paletteRam[0] = new int[]{ 0, 0, 0, 0 };

			// *this* is what the users actually write into when they write
			// to palette entry 0 to set the background color.
			bgColor = new int[]{ 0, 0, 0, 255 };

			// first 64 entries are the index, interpreted as RGB222.
			for(int i = 1; i < 64; i++) {
				int r = Intensities[(i >> 4) & 3];
				int g = Intensities[(i >> 2) & 3];
				int b = Intensities[i & 3];
				paletteRam[i] = new int[]{ r, g, b, 255 };
			}

			// next 16 entries are the classic display panel colors; so
			// you can convert classic colors to palette indexes by adding 64
			for(int i = 64; i < 80; i++) {
				var c = ClassicLEDDisplayPanel.PixelColors[i - 64];
				paletteRam[i] = new int[] { c[0], c[1], c[2], 255 };
			}

			// rest of first half of palette is pure black
			for(int i = 80; i < 128; i++) {
				paletteRam[i] = new int[] { 0, 0, 0, 255 };
			}

			// second half of palette is a smooth grayscale
			for(int i = 128; i < 256; i++) {
				int v = (i - 128) * 2;
				paletteRam[i] = new int[] { v, v, v, 255 };
			}
		}

		private void writePalette(int offs, int length, int value) {
			int entry = offs / 4;

			if(entry == 0) {
				// SPECIAL CASE for the BG color
				this.setColor(bgColor, offs, length, value);
				// the BG color entry is not used for anything other than the BG
				// color layer, so we don't have to mark the palette dirty.
			} else {
				this.setColor(paletteRam[entry], offs, length, value);
				isPalDirty = true;
			}
		}

		private void setColor(int[] color, int offs, int length, int value) {
			if(length == 4) {
				color[0] = (value >>> 16) & 0xFF;
				color[1] = (value >>> 8) & 0xFF;
				color[2] = value & 0xFF;
			} else if(length == 1) {
				// can't modify alpha
				if(offs < 3) {
					// 0 is blue, 1 is green, 2 is red
					color[2 - offs] = value & 0xFF;
				}
			} else if(offs == 0) {
				color[1] = (value >>> 8) & 0xFF;
				color[2] = value & 0xFF;
			} else {
				color[0] = value & 0xFF;
			}
		}

		// ----------------------------------------------------------------------------------------
		// Framebuffer methods

		private void clearFb() {
			Arrays.fill(fbRam, 0, fbRam.length, (byte)0);
			isFbDirty = true;
		}

		private void writeFb(int offs, int length, int value) {
			fbRam[offs] = (byte)(value & 0xFF);

			if(length > 1) {
				fbRam[offs + 1] = (byte)((value >>> 8) & 0xFF);

				if(length > 2) {
					fbRam[offs + 2] = (byte)((value >>> 16) & 0xFF);
					fbRam[offs + 3] = (byte)((value >>> 24) & 0xFF);
				}
			}

			isFbDirty = true;
		}

		private void buildFbLayer() {
			var r = fbLayer.getRaster();

			for(int y = 0; y < N_ROWS; y++) {
				for(int x = 0; x < N_COLUMNS; x++) {
					// yes, use the transparent color 0 if the index is 0;
					// the BG color will show through this image if so.
					// also have to do some Dumb Shit to zero extend the byte
					int colorIndex = ((int)fbRam[y*N_COLUMNS + x]) & 0xFF;
					r.setPixel(x, y, paletteRam[colorIndex]);
				}
			}

			isFbDirty = false;
		}

		// ----------------------------------------------------------------------------------------
		// Compositing methods

		// TODO
		private void compositeFrame() {
			// if the palette changed, everything has to change.
			if(isPalDirty) {
				if(fbEnabled)
					isFbDirty = true;
				if(tmEnabled)
					isTmDirty = true;

				isSprDirty = true;
			}

			if(fbEnabled && isFbDirty) {
				this.buildFbLayer();
			}

			// TODO
			// if(tmEnabled && isTmDirty) {
			// 	this.buildTmLayers();
			// }

			// TODO
			// if(isSprDirty) {
			// 	this.buildSpriteLayer();
			// }

			// composite all layers into the final image

			var g = finalImage.createGraphics();
			var bg = new Color(bgColor[0], bgColor[1], bgColor[2]);

			if(fbEnabled && !tmEnabled) {
				g.drawImage(fbLayer, 0, 0, N_COLUMNS, N_ROWS, bg, null);
				g.drawImage(spriteLayer, 0, 0, N_COLUMNS, N_ROWS, null);
			} else if(tmEnabled && !fbEnabled) {
				g.drawImage(tmLayerLo, 0, 0, N_COLUMNS, N_ROWS, bg, null);
				g.drawImage(spriteLayer, 0, 0, N_COLUMNS, N_ROWS, null);
				g.drawImage(tmLayerHi, 0, 0, N_COLUMNS, N_ROWS, null);
			} else if(fbInFront) {
				g.drawImage(tmLayerLo, 0, 0, N_COLUMNS, N_ROWS, bg, null);
				g.drawImage(spriteLayer, 0, 0, N_COLUMNS, N_ROWS, null);
				g.drawImage(tmLayerHi, 0, 0, N_COLUMNS, N_ROWS, null);
				g.drawImage(fbLayer, 0, 0, N_COLUMNS, N_ROWS, null);
			} else {
				g.drawImage(fbLayer, 0, 0, N_COLUMNS, N_ROWS, bg, null);
				g.drawImage(tmLayerLo, 0, 0, N_COLUMNS, N_ROWS, null);
				g.drawImage(spriteLayer, 0, 0, N_COLUMNS, N_ROWS, null);
				g.drawImage(tmLayerHi, 0, 0, N_COLUMNS, N_ROWS, null);
			}

			g.dispose();
			this.repaint();
		}
	}
}
