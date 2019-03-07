/* ###
 * IP: GHIDRA
 * REVIEWED: YES
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package docking.widgets.fieldpanel.field;

import java.awt.*;
import java.util.List;

import javax.swing.JComponent;

import docking.widgets.fieldpanel.internal.FieldBackgroundColorManager;
import docking.widgets.fieldpanel.internal.PaintContext;
import docking.widgets.fieldpanel.support.*;

/**
 * Field for showing multiple strings, each with its own attributes in a field,
 * on a single line, clipping as needed to fit within the field's width. Has the
 * extra methods for mapping column positions to strings and positions in those
 * strings.
 */
public class ClippingTextField implements TextField {
	private static int DOT_DOT_DOT_WIDTH = 12;

	private FieldElement originalElement;
	private FieldElement textElement;

	protected int startX;
	private int width;
	private int preferredWidth;

	private String fullText;
	private boolean isClipped;

	private HighlightFactory hlFactory;

	private boolean isPrimary;

	/**
	 * Constructs a new ClippingTextField that allows the cursor beyond the end
	 * of the line. This is just a pass through constructor that makes the call:
	 * 
	 * <pre>
	 * this(startX, width, new AttributedString[] { textElement }, hlFactory, true);
	 * </pre>
	 * 
	 * @param startX
	 *            The x position of the field
	 * @param width
	 *            The width of the field
	 * @param textElement
	 *            The AttributedStrings to display in the field.
	 * @param hlFactory
	 *            The HighlightFactory object used to paint highlights.
	 */
	public ClippingTextField(int startX, int width, FieldElement textElement,
			HighlightFactory hlFactory) {

		this.textElement = textElement;
		this.hlFactory = hlFactory;
		this.startX = startX;
		this.width = width;
		this.preferredWidth = textElement.getStringWidth();

		clip(width);
	}

	/**
	 * Checks if any of the textElements need to be clipped. If so, it creates a
	 * new textElement for the element that needs to be clipped that will fit in
	 * the available space. Any textElements past the clipped element will be
	 * ignored.
	 */
	private void clip(int availableWidth) {
		originalElement = textElement;
		int w = textElement.getStringWidth();

		if (w <= availableWidth) {
			return;
		}

		isClipped = true;
		int length = textElement.getMaxCharactersForWidth(width - DOT_DOT_DOT_WIDTH);
		textElement = textElement.substring(0, length);
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#contains(int, int)
	 */
	@Override
	public boolean contains(int x, int y) {
		if ((x >= startX) && (x < startX + width) && (y >= -textElement.getHeightAbove()) &&
			(y < textElement.getHeightBelow())) {
			return true;
		}
		return false;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getCol(int, int)
	 */
	@Override
	public int getCol(int row, int x) {
		int xPos = Math.max(x - startX, 0); // make x relative to this fields
		// coordinate system.
		return textElement.getMaxCharactersForWidth(xPos);
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getCursorBounds(int, int)
	 */
	@Override
	public Rectangle getCursorBounds(int row, int col) {
		if (row != 0) {
			return null;
		}

		int x = findX(col) + startX;

		return new Rectangle(x, -textElement.getHeightAbove(), 2, textElement.getHeightAbove() +
			textElement.getHeightBelow());
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getHeight()
	 */
	@Override
	public int getHeight() {
		return textElement.getHeightAbove() + textElement.getHeightBelow();
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getNumCols(int)
	 */
	@Override
	public int getNumCols(int row) {
		return getNumCols();
	}

	private int getNumCols() {
		return textElement.length() + 1; // allow one column past the end of the text
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getNumRows()
	 */
	@Override
	public int getNumRows() {
		return 1;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getRow(int)
	 */
	@Override
	public int getRow(int y) {
		return 0;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getScrollableUnitIncrement(int, int,
	 *      int)
	 */
	@Override
	public int getScrollableUnitIncrement(int topOfScreen, int direction, int max) {

		if ((topOfScreen < -getHeightAbove()) || (topOfScreen > getHeightBelow())) {
			return max;
		}

		if (direction > 0) { // if scrolling down
			return getHeightBelow() - topOfScreen;
		}

		return -getHeightAbove() - topOfScreen;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getStartX()
	 */
	@Override
	public int getStartX() {
		return startX;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getWidth()
	 */
	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public int getPreferredWidth() {
		return preferredWidth;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getX(int, int)
	 */
	@Override
	public int getX(int row, int col) {
		if (col >= getNumCols()) {
			col = getNumCols() - 1;
		}
		return findX(col) + startX;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getY(int)
	 */
	@Override
	public int getY(int row) {
		return -getHeightAbove();
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#isPrimary()
	 */
	@Override
	public boolean isPrimary() {
		return isPrimary;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.TextField#setPrimary(boolean)
	 */
	@Override
	public void setPrimary(boolean b) {
		isPrimary = b;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#isValid(int, int)
	 */
	@Override
	public boolean isValid(int row, int col) {
		if (row != 0) {
			return false;
		}

		return ((col >= 0) && (col < getNumCols()));
	}

	private String getString() {
		if (fullText == null) {
			fullText = originalElement.getText();
		}
		return fullText;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#paint(java.awt.Graphics,
	 *      docking.widgets.fieldpanel.internal.PaintContext, boolean,
	 *      docking.widgets.fieldpanel.support.RowColLocation)
	 */
	@Override
	public void paint(JComponent c, Graphics g, PaintContext context,
			FieldBackgroundColorManager colorManager, RowColLocation cursorLoc, int rowHeight) {
		if (context.isPrinting()) {
			print(g, context);
		}
		else {
			paintSelection(g, colorManager, 0, rowHeight);
			paintHighlights(g, cursorLoc);
			paintText(c, g, context);
			paintCursor(g, context.getCursorColor(), cursorLoc);
		}
	}

	void print(Graphics g, PaintContext context) {
		// TODO fix printing
		textElement.paint(null, g, startX, 0);
		if (isClipped) {
			paintDots(g, startX + textElement.getStringWidth());
		}

	}

	void paintText(JComponent c, Graphics g, PaintContext context) {
		textElement.paint(c, g, startX, 0);

		if (isClipped) {
			g.setColor(textElement.getColor(textElement.length() - 1));
			paintDots(g, startX + textElement.getStringWidth());
		}
	}

	private void paintDots(Graphics g, int x) {
		int pos = 1; // skip one pixel
		for (int i = 0; i < 3; i++) {
			if (pos < DOT_DOT_DOT_WIDTH - 2) { // don't paint too close to next
				// field.
				g.drawRect(x + pos, -2, 1, 1);
				pos += 4; // add in size of dot and padding
			}
		}
	}

	private void paintHighlights(Graphics g, RowColLocation cursorLoc) {
		int cursorTextOffset = -1;
		if (cursorLoc != null) {
			cursorTextOffset = screenLocationToTextOffset(cursorLoc.row(), cursorLoc.col());
		}
		paintHighlights(g, hlFactory.getHighlights(getString(), cursorTextOffset));
	}

	protected void paintSelection(Graphics g, FieldBackgroundColorManager colorManager, int row,
			int rowHeight) {

		List<Highlight> selections = colorManager.getSelectionHighlights(row);
		if (selections.isEmpty()) {
			return;
		}
		int textLength = getString().length();
		int endTextPos = findX(textLength);
		for (Highlight highlight : selections) {
			g.setColor(highlight.getColor());
			int startCol = highlight.getStart();
			int endCol = highlight.getEnd();
			int x1 = findX(startCol);
			int x2 = endCol < textLength ? findX(endCol) : endTextPos;
			g.fillRect(startX + x1, -getHeightAbove(), x2 - x1, getHeight());
		}

		Color rightMarginColor = colorManager.getPaddingColor(1);
		if (rightMarginColor != null) {
			g.setColor(rightMarginColor);
			g.fillRect(startX + endTextPos, -getHeightAbove(), width - endTextPos, rowHeight);
		}
	}

	protected void paintHighlights(Graphics g, Highlight[] highlights) {
		for (int i = 0; i < highlights.length; i++) {
			int startCol = Math.max(highlights[i].getStart(), 0);
			int endCol = Math.min(highlights[i].getEnd(), getString().length());
			Color c = highlights[i].getColor();
			if (endCol >= startCol) {
				int start = findX(startCol);
				int end = findX(endCol + 1);
				if (isClipped && endCol >= getNumCols()) {
					end += DOT_DOT_DOT_WIDTH;
				}
				g.setColor(c);
				g.fillRect(startX + start, -getHeightAbove(), end - start, getHeight());
			}
		}
	}

	protected void paintCursor(Graphics g, Color cursorColor, RowColLocation cursorLoc) {
		if (cursorLoc != null) {
			g.setColor(cursorColor);
			if (cursorLoc.col() < getNumCols()) {
				int x = startX + findX(cursorLoc.col());
				g.fillRect(x, -getHeightAbove(), 2, getHeight());
			}
		}
	}

	/**
	 * Converts a single column value into a MultiStringLocation which specifies
	 * a string index and a column position within that string.
	 * 
	 * @param screenColumn
	 *            the overall column position in the total String.
	 * @return MultiStringLocation the MultiStringLocation corresponding to the
	 *         given column.
	 */
	@Override
	public RowColLocation screenToDataLocation(int screenRow, int screenColumn) {
		return textElement.getDataLocationForCharacterIndex(screenColumn);

	}

	/**
	 * @see docking.widgets.fieldpanel.field.TextField#dataToScreenLocation(int, int)
	 */
	@Override
	public RowColLocation dataToScreenLocation(int dataRow, int dataColumn) {
		int column = textElement.getCharacterIndexForDataLocation(dataRow, dataColumn);
		return new RowColLocation(0, Math.max(column, 0));
	}

	private int findX(int col) {
		if (col > textElement.length()) {
			col = textElement.length();
		}
		return textElement.substring(0, col).getStringWidth();
	}

	/**
	 * Returns true if the text is clipped (truncated)
	 */
	@Override
	public boolean isClipped() {
		return isClipped;
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getHeightAbove()
	 */
	@Override
	public int getHeightAbove() {
		return textElement.getHeightAbove();
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getHeightBelow()
	 */
	@Override
	public int getHeightBelow() {
		return textElement.getHeightBelow();
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#rowHeightChanged(int, int)
	 */
	@Override
	public void rowHeightChanged(int heightAbove, int heightBelow) {
		// Don't care
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#getText()
	 */
	@Override
	public String getText() {
		return getString();
	}

	@Override
	public String getTextWithLineSeparators() {
		return getString();
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#textOffsetToScreenLocation(int)
	 */
	@Override
	public RowColLocation textOffsetToScreenLocation(int textOffset) {
		return new RowColLocation(0, Math.min(textOffset, textElement.getText().length() - 1));
	}

	/**
	 * @see docking.widgets.fieldpanel.field.Field#screenLocationToTextOffset(int, int)
	 */
	@Override
	public int screenLocationToTextOffset(int row, int col) {
		return Math.min(textElement.getText().length(), col);
	}

	/** 
	 * @see ghidra.app.util.viewer.field.ListingField#getClickedObject(FieldLocation)
	 */
	public Object getClickedObject(FieldLocation fieldLocation) {
		return getFieldElement(fieldLocation.row, fieldLocation.col);
	}

	@Override
	public FieldElement getFieldElement(int screenRow, int screenColumn) {
// TODO - this used to return the clipped value, which is not our clients wanted (at least one). If
//		  any odd navigation/tracking/action issues appear, then this could be the culprit.
//		return textElement.getFieldElement(screenColumn);
		return originalElement.getFieldElement(screenColumn);
	}

	@Override
	public String toString() {
		return getText();
	}
}
