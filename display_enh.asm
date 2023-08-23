
# -------------------------------------------------------------------------------------------------

# a0 = key to check
# returns 1 if held, 0 if not
display_is_key_held:
	sw a0, DISPLAY_KEY_HELD
	lw v0, DISPLAY_KEY_HELD
jr ra

# -------------------------------------------------------------------------------------------------

# a0 = key to check
# returns 1 if pressed on this frame, 0 if not
display_is_key_pressed:
	sw a0, DISPLAY_KEY_PRESSED
	lw v0, DISPLAY_KEY_PRESSED
jr ra

# -------------------------------------------------------------------------------------------------

# a0 = key to check
# returns 1 if released on this frame, 0 if not
display_is_key_released:
	sw a0, DISPLAY_KEY_RELEASED
	lw v0, DISPLAY_KEY_RELEASED
jr ra

# -------------------------------------------------------------------------------------------------

# Loads palette entries into palette RAM. Each palette entry is a word in the format
# 0xRRGGBB, e.g. 0xFF0000 is pure red, 0x00FF00 is pure green, etc.
# a0 = address of palette array to load (use la for this argument)
# a1 = start color index to load it into. don't forget, index 0 is the background color!
# a2 = number of colors. shouldn't be < 1 or > 256, or else weird shit happens
display_load_palette:
	mul a1, a1, 4
	add a1, a1, DISPLAY_PALETTE_RAM

	_loop:
		lw t0, (a0)
		sw t0, (a1)
		add a0, a0, 4
		add a1, a1, 4
	sub a2, a2, 1
	bgt a2, 0, _loop
jr ra

# -------------------------------------------------------------------------------------------------

# sets 1 pixel to a given color.
# (0, 0) is in the top LEFT, and Y increases DOWNWARDS!
# arguments:
#	a0 = x
#	a1 = y
#	a2 = color
display_set_pixel:
	blt a0, 0, _return
	bge a0, DISPLAY_W, _return
	blt a1, 0, _return
	bge a1, DISPLAY_H, _return

	sll t0, a1, DISPLAY_W_SHIFT
	add t0, t0, a0
	add t0, t0, DISPLAY_FB_RAM
	sb  a2, (t0)
_return:
	jr  ra