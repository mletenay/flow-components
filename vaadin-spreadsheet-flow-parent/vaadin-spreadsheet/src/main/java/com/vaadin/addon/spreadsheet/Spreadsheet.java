package com.vaadin.addon.spreadsheet;

/*
 * #%L
 * Vaadin Spreadsheet
 * %%
 * Copyright (C) 2013 - 2015 Vaadin Ltd
 * %%
 * This program is available under Commercial Vaadin Add-On License 3.0
 * (CVALv3).
 * 
 * See the file license.html distributed with this software for more
 * information about licensing.
 * 
 * You should have received a copy of the CVALv3 along with this program.
 * If not, see <http://vaadin.com/license/cval-3>.
 * #L%
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.poi.hssf.converter.ExcelToHtmlUtils;
import org.apache.poi.hssf.record.cf.CellRangeUtil;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.PaneInformation;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.impl.values.XmlValueDisconnectedException;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Element;

import com.vaadin.addon.spreadsheet.client.ImageInfo;
import com.vaadin.addon.spreadsheet.client.MergedRegion;
import com.vaadin.addon.spreadsheet.client.MergedRegionUtil.MergedRegionContainer;
import com.vaadin.addon.spreadsheet.client.SpreadsheetClientRpc;
import com.vaadin.addon.spreadsheet.client.SpreadsheetState;
import com.vaadin.addon.spreadsheet.command.SizeChangeCommand;
import com.vaadin.addon.spreadsheet.command.SizeChangeCommand.Type;
import com.vaadin.event.Action;
import com.vaadin.event.Action.Handler;
import com.vaadin.server.Resource;
import com.vaadin.server.StreamResource;
import com.vaadin.server.StreamResource.StreamSource;
import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.Component;
import com.vaadin.ui.HasComponents;
import com.vaadin.ui.declarative.DesignAttributeHandler;
import com.vaadin.ui.declarative.DesignContext;
import com.vaadin.util.ReflectTools;

/**
 * Vaadin Spreadsheet is a Vaadin Add-On Component which allows displaying and
 * interacting with the contents of an Excel file. The Spreadsheet can be used
 * in any Vaadin application for enabling users to view and manipulate Excel
 * files in their web browsers.
 * 
 * @author Vaadin Ltd.
 */
@SuppressWarnings("serial")
public class Spreadsheet extends AbstractComponent implements HasComponents,
        Action.Container {

    /**
     * This is a style which hides the top (address and formula) bar.
     */
    public static final String HIDE_FUNCTION_BAR_STYLE = "hidefunctionbar";

    /**
     * This is a style which hides the bottom (sheet selection) bar.
     */
    public static final String HIDE_TABSHEET_STYLE = "hidetabsheet";

    private static final Logger LOGGER = Logger.getLogger(Spreadsheet.class
            .getName());

    /**
     * An interface for handling the edited cell value from user input.
     */
    public interface CellValueHandler extends Serializable {

        /**
         * Called if a cell value has been edited by the user by using the
         * default cell editor. Use
         * {@link Spreadsheet#setCellValueHandler(CellValueHandler)} to enable
         * it for the spreadsheet.
         * 
         * @param cell
         *            The cell that has been edited, may be <code>null</code> if
         *            the cell doesn't yet exists
         * @param sheet
         *            The sheet the cell belongs to, the currently active sheet
         * @param colIndex
         *            Cell column index, 0-based
         * @param rowIndex
         *            Cell row index, 0-based
         * @param newValue
         *            The value user has entered
         * @param formulaEvaluator
         *            The {@link FormulaEvaluator} for this sheet
         * @param formatter
         *            The {@link DataFormatter} for this workbook
         * @return <code>true</code> if component default parsing should still
         *         be done, <code>false</code> if not
         */
        public boolean cellValueUpdated(Cell cell, Sheet sheet, int colIndex,
                int rowIndex, String newValue,
                FormulaEvaluator formulaEvaluator, DataFormatter formatter);
    }

    /**
     * An interface for handling clicks on cells that contain a hyperlink.
     * <p>
     * Implement this interface and use
     * {@link Spreadsheet#setHyperlinkCellClickHandler(HyperlinkCellClickHandler)}
     * to enable it for the spreadsheet.
     */
    public interface HyperlinkCellClickHandler extends Serializable {

        /**
         * Called when a hyperlink cell has been clicked.
         * 
         * @param cell
         *            The cell that contains the hyperlink
         * @param hyperlink
         *            The actual hyperlink
         * @param spreadsheet
         *            The Spreadsheet the cell is in
         */
        public void onHyperLinkCellClick(Cell cell, Hyperlink hyperlink,
                Spreadsheet spreadsheet);
    }

    private SpreadsheetStyleFactory styler;
    private HyperlinkCellClickHandler hyperlinkCellClickHandler;
    private SpreadsheetComponentFactory customComponentFactory;

    private final CellSelectionManager selectionManager = new CellSelectionManager(
            this);
    private final CellValueManager valueManager = new CellValueManager(this);
    private final CellSelectionShifter cellShifter = new CellSelectionShifter(
            this);
    private final ContextMenuManager contextMenuManager = new ContextMenuManager(
            this);
    private final SpreadsheetHistoryManager historyManager = new SpreadsheetHistoryManager(
            this);
    private ConditionalFormatter conditionalFormatter;

    private int firstRow;
    private int lastRow;
    private int firstColumn;
    private int lastColumn;

    /**
     * This is used for making sure the cells are sent to client side in when
     * the next cell data request comes. This is triggered when the client side
     * connector init() method is run.
     */
    private boolean reloadCellDataOnNextScroll;

    private int defaultNewSheetRows = SpreadsheetFactory.DEFAULT_ROWS;
    private int defaultNewSheetColumns = SpreadsheetFactory.DEFAULT_COLUMNS;

    private boolean topLeftCellCommentsLoaded;
    private boolean topLeftCellHyperlinksLoaded;

    protected int mergedRegionCounter;

    private Workbook workbook;

    /** true if the component sheet should be reloaded on client side. */
    private boolean reload;

    /** are tables for currently active sheet loaded */
    private boolean tablesLoaded;

    /** image sizes need to be recalculated on column/row resizing s */
    private boolean reloadImageSizesFromPOI;

    private String defaultPercentageFormat = "0.00%";

    protected String initialSheetSelection = null;

    private HashSet<Component> customComponents;

    private Map<CellReference, PopupButton> sheetPopupButtons = new HashMap<CellReference, PopupButton>();

    /**
     * Set of images contained in the currently active sheet.
     */
    protected HashSet<SheetImageWrapper> sheetImages;

    private HashSet<SpreadsheetTable> tables;

    private String srcUri;

    private boolean defaultColWidthSet, defaultRowHeightSet;

    /**
     * Container for merged regions for the currently active sheet.
     */
    protected final MergedRegionContainer mergedRegionContainer = new MergedRegionContainer() {

        /*
         * (non-Javadoc)
         * 
         * @see com.vaadin.addon.spreadsheet.client.MergedRegionUtil.
         * MergedRegionContainer#getMergedRegionStartingFrom(int, int)
         */
        @Override
        public MergedRegion getMergedRegionStartingFrom(int column, int row) {
            List<MergedRegion> mergedRegions = getState(false).mergedRegions;
            if (mergedRegions != null) {
                for (MergedRegion region : mergedRegions) {
                    if (region.col1 == column && region.row1 == row) {
                        return region;
                    }
                }
            }
            return null;
        }

        /*
         * (non-Javadoc)
         * 
         * @see com.vaadin.addon.spreadsheet.client.MergedRegionUtil.
         * MergedRegionContainer#getMergedRegion(int, int)
         */
        @Override
        public MergedRegion getMergedRegion(int column, int row) {
            List<MergedRegion> mergedRegions = getState(false).mergedRegions;
            if (mergedRegions != null) {
                for (MergedRegion region : mergedRegions) {
                    if (region.col1 <= column && region.row1 <= row
                            && region.col2 >= column && region.row2 >= row) {
                        return region;
                    }
                }
            }
            return null;
        }
    };

    /**
     * Creates a new Spreadsheet component using the newer Excel version format
     * {@link XSSFWorkbook} and default row
     * {@link SpreadsheetFactory#DEFAULT_ROWS} and column
     * {@link SpreadsheetFactory#DEFAULT_COLUMNS} counts.
     */
    public Spreadsheet() {
        sheetImages = new HashSet<SheetImageWrapper>();
        tables = new HashSet<SpreadsheetTable>();

        registerRpc(new SpreadsheetHandlerImpl(this));
        setSizeFull(); // Default to full size

        SpreadsheetFactory.loadSpreadsheetWith(this, null);
    }

    /**
     * Creates a new Spreadsheet component and loads the given Workbook.
     * 
     * @param workbook
     *            Workbook to load
     */
    public Spreadsheet(Workbook workbook) {
        sheetImages = new HashSet<SheetImageWrapper>();
        tables = new HashSet<SpreadsheetTable>();

        registerRpc(new SpreadsheetHandlerImpl(this));
        setSizeFull(); // Default to full size

        SpreadsheetFactory.loadSpreadsheetWith(this, workbook);
    }

    /**
     * Creates a new Spreadsheet component and loads the given Excel file.
     * 
     * @param file
     *            Excel file
     * @throws IOException
     *             If file has invalid format or there is no access to the file
     */
    public Spreadsheet(File file) throws IOException {
        this();
        SpreadsheetFactory.reloadSpreadsheetComponent(this, file);
        srcUri = file.toURI().toString();
    }

    /**
     * Creates a new Spreadsheet component based on the given input stream. The
     * expected format is that of an Excel file.
     * 
     * @param inputStream
     *            Stream that provides Excel-formatted data.
     * @throws IOException
     *             If there is an error handling the stream, or if the data is
     *             in an invalid format.
     */
    public Spreadsheet(InputStream inputStream) throws IOException {
        this();
        SpreadsheetFactory.reloadSpreadsheetComponent(this, inputStream);
    }

    /**
     * Adds an action handler to the spreadsheet that handles the event produced
     * by the context menu (right click) on cells and row and column headers.
     * The action handler is component, not workbook, specific.
     * <p>
     * The parameters on the
     * {@link Handler#handleAction(Action, Object, Object)} and
     * {@link Handler#getActions(Object, Object)} depend on the actual target of
     * the right click.
     * <p>
     * The second parameter (sender) on
     * {@link Handler#getActions(Object, Object)} is always the spreadsheet
     * component. In case of a cell, the first parameter (target) on contains
     * the latest {@link SelectionChangeEvent} for the spreadsheet. In case of a
     * row or a column header, the first parameter (target) is a
     * {@link CellRangeAddress}. To distinct between column / row header, you
     * can use {@link CellRangeAddress#isFullColumnRange()} and
     * {@link CellRangeAddress#isFullRowRange()}.
     * <p>
     * Similarly for {@link Handler#handleAction(Action, Object, Object)} the
     * second parameter (sender) is always the spreadsheet component. The third
     * parameter (target) is the latest {@link SelectionChangeEvent} for the
     * spreadsheet, or the {@link CellRangeAddress} defining the selected row /
     * column header.
     */
    @Override
    public void addActionHandler(Handler actionHandler) {
        contextMenuManager.addActionHandler(actionHandler);
        getState().hasActions = contextMenuManager.hasActionHandlers();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.vaadin.event.Action.Container#removeActionHandler(com.vaadin.event
     * .Action.Handler)
     */
    @Override
    public void removeActionHandler(Handler actionHandler) {
        contextMenuManager.removeActionHandler(actionHandler);
        getState().hasActions = contextMenuManager.hasActionHandlers();
    }

    /**
     * Sets the {@link CellValueHandler} for this component (not workbook/sheet
     * specific). It is called when a cell's value has been updated by the user
     * by using the spreadsheet component's default editor (text input).
     * 
     * @param customCellValueHandler
     *            New handler or <code>null</code> if none should be used
     */
    public void setCellValueHandler(CellValueHandler customCellValueHandler) {
        getCellValueManager().setCustomCellValueHandler(customCellValueHandler);
    }

    /**
     * See {@link CellValueHandler}.
     * 
     * @return the current {@link CellValueHandler} for this component or
     *         <code>null</code> if none has been set
     */
    public CellValueHandler getCellValueHandler() {
        return getCellValueManager().getCustomCellValueHandler();
    }

    /**
     * Sets the {@link HyperlinkCellClickHandler} for this component (not
     * workbook/sheet specific). It's called when the user click a cell that is
     * a hyperlink.
     * 
     * @param hyperLinkCellClickHandler
     *            New handler or <code>null</code> if none should be used
     */
    public void setHyperlinkCellClickHandler(
            HyperlinkCellClickHandler hyperLinkCellClickHandler) {
        hyperlinkCellClickHandler = hyperLinkCellClickHandler;
    }

    /**
     * See {@link HyperlinkCellClickHandler}.
     * 
     * @return the current {@link HyperlinkCellClickHandler} for this component
     *         or <code>null</code> if none has been set
     */
    public HyperlinkCellClickHandler getHyperlinkCellClickHandler() {
        return hyperlinkCellClickHandler;
    }

    /**
     * Gets the ContextMenuManager for this Spreadsheet. This is component (not
     * workbook/sheet) specific.
     * 
     * @return The ContextMenuManager
     */
    public ContextMenuManager getContextMenuManager() {
        return contextMenuManager;
    }

    /**
     * Gets the CellSelectionManager for this Spreadsheet. This is component
     * (not workbook/sheet) specific.
     * 
     * @return The CellSelectionManager
     */
    public CellSelectionManager getCellSelectionManager() {
        return selectionManager;
    }

    /**
     * Gets the CellValueManager for this Spreadsheet. This is component (not
     * workbook/sheet) specific.
     * 
     * @return The CellValueManager
     */
    public CellValueManager getCellValueManager() {
        return valueManager;
    }

    /**
     * Gets the CellShifter for this Spreadsheet. This is component (not
     * workbook/sheet) specific.
     * 
     * @return The CellShifter
     */
    protected CellSelectionShifter getCellShifter() {
        return cellShifter;
    }

    /**
     * Gets the SpreadsheetHistoryManager for this Spreadsheet. This is
     * component (not workbook/sheet) specific.
     * 
     * @return The SpreadsheetHistoryManager
     */
    public SpreadsheetHistoryManager getSpreadsheetHistoryManager() {
        return historyManager;
    }

    /**
     * Gets the MergedRegionContainer for this Spreadsheet. This is component
     * (not workbook/sheet) specific.
     * 
     * @return The MergedRegionContainer
     */
    protected MergedRegionContainer getMergedRegionContainer() {
        return mergedRegionContainer;
    }

    /**
     * Returns the first visible column in the main scroll area (NOT freeze
     * pane)
     * 
     * @return Index of first visible column, 1-based
     */
    public int getFirstColumn() {
        return firstColumn;
    }

    /**
     * Returns the last visible column in the main scroll area (NOT freeze pane)
     * 
     * @return Index of last visible column, 1-based
     */
    public int getLastColumn() {
        return lastColumn;
    }

    /**
     * Returns the first visible row in the scroll area (not freeze pane)
     * 
     * @return Index of first visible row, 1-based
     */
    public int getFirstRow() {
        return firstRow;
    }

    /**
     * Returns the last visible row in the main scroll area (NOT freeze pane)
     * 
     * @return Index of last visible row, 1-based
     */
    public int getLastRow() {
        return lastRow;
    }

    /**
     * Returns the position of the vertical split (freeze pane). NOTE: this is
     * the opposite from POI, this is the last ROW that is frozen.
     * 
     * @return Last frozen row or 0 if none
     */
    public int getVerticalSplitPosition() {
        return getState(false).verticalSplitPosition;
    }

    /**
     * Returns the position of the horizontal split (freeze pane). NOTE: this is
     * the opposite from POI, this is the last COLUMN that is frozen.
     * 
     * @return Last frozen column or 0 if none
     */
    public int getHorizontalSplitPosition() {
        return getState(false).horizontalSplitPosition;
    }

    /**
     * Returns true if the component is being fully re-rendered after this
     * round-trip (sheet change etc.)
     * 
     * @return true if re-render will happen, false otherwise
     */
    public boolean isRerenderPending() {
        return reload;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.vaadin.server.AbstractClientConnector#fireEvent(java.util.EventObject
     * )
     */
    @Override
    protected void fireEvent(EventObject event) {
        super.fireEvent(event);
    }

    /**
     * This method is called when the sheet is scrolled. It takes care of
     * sending newly revealed data to the client side.
     * 
     * @param firstRow
     *            Index of first visible row after the scroll, 1-based
     * @param firstColumn
     *            Index of first visible column after the scroll, 1-based
     * @param lastRow
     *            Index of last visible row after the scroll, 1-based
     * @param lastColumn
     *            Index of first visible column after the scroll, 1-based
     */
    protected void onSheetScroll(int firstRow, int firstColumn, int lastRow,
            int lastColumn) {
        if (reloadCellDataOnNextScroll || this.firstRow != firstRow
                || this.lastRow != lastRow || this.firstColumn != firstColumn
                || this.lastColumn != lastColumn) {
            this.firstRow = firstRow;
            this.lastRow = lastRow;
            this.firstColumn = firstColumn;
            this.lastColumn = lastColumn;
            loadCells(firstRow, firstColumn, lastRow, lastColumn);
        }
        if (initialSheetSelection != null) {
            selectionManager.onSheetAddressChanged(initialSheetSelection);
            initialSheetSelection = null;
        } else if (reloadCellDataOnNextScroll) {
            selectionManager.reloadCurrentSelection();
        }
        reloadCellDataOnNextScroll = false;
    }

    /**
     * Tells whether the given cell range is editable or not.
     * 
     * @param cellRangeAddress
     *            Cell range to test
     * @return True if range is editable, false otherwise.
     */
    protected boolean isRangeEditable(CellRangeAddress cellRangeAddress) {
        return isRangeEditable(cellRangeAddress.getFirstRow(),
                cellRangeAddress.getFirstColumn(),
                cellRangeAddress.getLastRow(), cellRangeAddress.getLastColumn());
    }

    /**
     * Determines if the given cell range is editable or not.
     * 
     * @param row1
     *            Index of starting row, 0-based
     * @param col1
     *            Index of starting column, 0-based
     * @param row2
     *            Index of ending row, 0-based
     * @param col2
     *            Index of ending column, 0-based
     * 
     * @return True if the whole range is editable, false otherwise.
     */
    protected boolean isRangeEditable(int row1, int col1, int row2, int col2) {
        if (isActiveSheetProtected()) {
            for (int r = row1; r <= row2; r++) {
                final Row row = getActiveSheet().getRow(r);
                if (row != null) {
                    for (int c = col1; c <= col2; c++) {
                        final Cell cell = row.getCell(c);
                        if (isCellLocked(cell)) {
                            return false;
                        }
                    }
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Creates a CellRangeAddress from the given cell address string. Also
     * checks that the range is valid within the currently active sheet. If it
     * is not, the resulting range will be truncated to fit the active sheet.
     * 
     * @param addressString
     *            Cell address string, e.g. "B3:C5"
     * @return A CellRangeAddress based on the given coordinates.
     */
    protected CellRangeAddress createCorrectCellRangeAddress(
            String addressString) {
        final String[] split = addressString.split(":");
        final CellReference cr1 = new CellReference(split[0]);
        final CellReference cr2 = new CellReference(split[1]);
        int r1 = cr1.getRow() > cr2.getRow() ? cr2.getRow() : cr1.getRow();
        int r2 = cr1.getRow() > cr2.getRow() ? cr1.getRow() : cr2.getRow();
        int c1 = cr1.getCol() > cr2.getCol() ? cr2.getCol() : cr1.getCol();
        int c2 = cr1.getCol() > cr2.getCol() ? cr1.getCol() : cr2.getCol();
        if (r1 >= getState().rows) {
            r1 = getState().rows - 1;
        }
        if (r2 >= getState().rows) {
            r2 = getState().rows - 1;
        }
        if (c1 >= getState().cols) {
            c1 = getState().cols - 1;
        }
        if (c2 >= getState().cols) {
            c2 = getState().cols - 1;
        }
        return new CellRangeAddress(r1, r2, c1, c2);
    }

    /**
     * Creates a CellRangeAddress from the given start and end coordinates. Also
     * checks that the range is valid within the currently active sheet. If it
     * is not, the resulting range will be truncated to fit the active sheet.
     * 
     * @param row1
     *            Index of the starting row, 1-based
     * @param col1
     *            Index of the starting column, 1-based
     * @param row2
     *            Index of the ending row, 1-based
     * @param col2
     *            Index of the ending column, 1-based
     * 
     * @return A CellRangeAddress based on the given coordinates.
     */
    protected CellRangeAddress createCorrectCellRangeAddress(int row1,
            int col1, int row2, int col2) {
        int r1 = row1 > row2 ? row2 : row1;
        int r2 = row1 > row2 ? row1 : row2;
        int c1 = col1 > col2 ? col2 : col1;
        int c2 = col1 > col2 ? col1 : col2;
        if (r1 >= getState().rows) {
            r1 = getState().rows;
        }
        if (r2 >= getState().rows) {
            r2 = getState().rows;
        }
        if (c1 >= getState().cols) {
            c1 = getState().cols;
        }
        if (c2 >= getState().cols) {
            c2 = getState().cols;
        }
        return new CellRangeAddress(r1 - 1, r2 - 1, c1 - 1, c2 - 1);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#getState()
     */
    @Override
    protected SpreadsheetState getState() {
        return (SpreadsheetState) super.getState();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#getState(boolean)
     */
    @Override
    protected SpreadsheetState getState(boolean markAsDirty) {
        return (SpreadsheetState) super.getState(markAsDirty);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#setLocale(java.util.Locale)
     */
    @Override
    public void setLocale(Locale locale) {
        super.setLocale(locale);
        valueManager.updateFormatter(locale);
        refreshAllCellValues();
    }

    /**
     * See {@link Workbook#setSheetHidden(int, int)}.
     * <p>
     * Gets the Workbook with {@link #getWorkbook()} and uses its API to access
     * status on currently visible/hidden/very hidden sheets.
     * 
     * If the currently active sheet is set hidden, another sheet is set as
     * active sheet automatically. At least one sheet should be always visible.
     * 
     * @param hidden
     *            Visibility state to set: 0-visible, 1-hidden, 2-very hidden.
     * @param sheetPOIIndex
     *            Index of the target sheet within the POI model, 0-based
     * @throws IllegalArgumentException
     *             If the index or state is invalid, or if trying to hide the
     *             only visible sheet.
     */
    public void setSheetHidden(int sheetPOIIndex, int hidden)
            throws IllegalArgumentException {
        // POI allows user to hide all sheets ...
        if (hidden != 0
                && SpreadsheetUtil.getNumberOfVisibleSheets(workbook) == 1) {
            throw new IllegalArgumentException(
                    "At least one sheet should be always visible.");
        }
        boolean isHidden = workbook.isSheetHidden(sheetPOIIndex);
        boolean isVeryHidden = workbook.isSheetVeryHidden(sheetPOIIndex);
        int activeSheetIndex = workbook.getActiveSheetIndex();
        workbook.setSheetHidden(sheetPOIIndex, hidden);

        // skip component reload if "nothing changed"
        if (hidden == 0 && (isHidden || isVeryHidden) || hidden != 0
                && !(isHidden && isVeryHidden)) {
            if (sheetPOIIndex != activeSheetIndex) {
                reloadSheetNames();
                getState().sheetIndex = getSpreadsheetSheetIndex(activeSheetIndex) + 1;
            } else { // the active sheet can be only set as hidden
                int oldVisibleSheetIndex = getState().sheetIndex - 1;
                if (hidden != 0
                        && activeSheetIndex == (workbook.getNumberOfSheets() - 1)) {
                    // hiding the active sheet, and it was the last sheet
                    oldVisibleSheetIndex--;
                }
                int newActiveSheetIndex = getVisibleSheetPOIIndex(oldVisibleSheetIndex);
                workbook.setActiveSheet(newActiveSheetIndex);
                reloadActiveSheetData();
                SpreadsheetFactory
                        .reloadSpreadsheetData(this, getActiveSheet());
            }
        }
    }

    /**
     * Returns an array containing the names of the currently visible sheets.
     * Does not contain the names of hidden or very hidden sheets.
     * <p>
     * To get all of the current {@link Workbook}'s sheet names, you should
     * access the POI API with {@link #getWorkbook()}.
     * 
     * @return Names of the currently visible sheets.
     */
    public String[] getVisibleSheetNames() {
        final String[] names = getState(false).sheetNames;
        return Arrays.copyOf(names, names.length);
    }

    /**
     * Sets a name for the sheet at the given visible sheet index.
     * 
     * @param sheetIndex
     *            Index of the target sheet among the visible sheets, 0-based
     * @param sheetName
     *            New sheet name. Not null, empty nor longer than 31 characters.
     *            Must be unique within the Workbook.
     * @throws IllegalArgumentException
     *             If the index is invalid, or if the sheet name is invalid. See
     *             {@link WorkbookUtil#validateSheetName(String)}.
     */
    public void setSheetName(int sheetIndex, String sheetName)
            throws IllegalArgumentException {
        if (sheetIndex < 0 || sheetIndex >= getState().sheetNames.length) {
            throw new IllegalArgumentException("Invalid Sheet index given.");
        }
        int poiSheetIndex = getVisibleSheetPOIIndex(sheetIndex);
        setSheetNameWithPOIIndex(poiSheetIndex, sheetName);
    }

    /**
     * Sets a name for the sheet at the given POI model index.
     * 
     * @param sheetIndex
     *            Index of the target sheet within the POI model, 0-based
     * @param sheetName
     *            New sheet name. Not null, empty nor longer than 31 characters.
     *            Must be unique within the Workbook.
     * @throws IllegalArgumentException
     *             If the index is invalid, or if the sheet name is invalid. See
     *             {@link WorkbookUtil#validateSheetName(String)}.
     * 
     */
    public void setSheetNameWithPOIIndex(int sheetIndex, String sheetName)
            throws IllegalArgumentException {
        if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
            throw new IllegalArgumentException("Invalid POI Sheet index given.");
        }
        if (sheetName == null || sheetName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Sheet Name cannot be null or an empty String, or contain backslash \\.");
        }
        if (isSheetNameExisting(sheetName)) {
            throw new IllegalArgumentException(
                    "Sheet name must be unique within the workbook.");
        }
        workbook.setSheetName(sheetIndex, sheetName);
        if (!workbook.isSheetVeryHidden(sheetIndex)
                && !workbook.isSheetHidden(sheetIndex)) {
            int ourIndex = getSpreadsheetSheetIndex(sheetIndex);
            getState().sheetNames[ourIndex] = sheetName;
        }
    }

    /**
     * Sets the protection enabled with the given password for the sheet at the
     * given index. <code>null</code> password removes the protection.
     * 
     * @param sheetPOIIndex
     *            Index of the target sheet within the POI model, 0-based
     * @param password
     *            The password to set for the protection. Pass <code>null</code>
     *            to remove the protection.
     */
    public void setSheetProtected(int sheetPOIIndex, String password) {
        if (sheetPOIIndex < 0 || sheetPOIIndex >= workbook.getNumberOfSheets()) {
            throw new IllegalArgumentException("Invalid POI Sheet index given.");
        }
        workbook.getSheetAt(sheetPOIIndex).protectSheet(password);
        getState().sheetProtected = getActiveSheet().getProtect();
        // if the currently active sheet was protected, the protection for the
        // currently selected cell might have changed
        if (sheetPOIIndex == workbook.getActiveSheetIndex()) {
            loadCustomComponents();
            selectionManager.reSelectSelectedCell();
        }
    }

    /**
     * Sets the protection enabled with the given password for the currently
     * active sheet. <code>null</code> password removes the protection.
     * 
     * @param password
     *            The password to set for the protection. Pass <code>null</code>
     *            to remove the protection.
     */
    public void setActiveSheetProtected(String password) {
        setSheetProtected(workbook.getActiveSheetIndex(), password);
    }

    /**
     * Creates a new sheet as the last sheet and sets it as the active sheet.
     * 
     * If the sheetName given is null, then the sheet name is automatically
     * generated by Apache POI in {@link Workbook#createSheet()}.
     * 
     * @param sheetName
     *            Can be null, but not empty nor longer than 31 characters. Must
     *            be unique within the Workbook.
     * @param rows
     *            Number of rows the sheet should have
     * @param columns
     *            Number of columns the sheet should have
     * @throws IllegalArgumentException
     *             If the sheet name is empty or over 31 characters long or not
     *             unique.
     */
    public void createNewSheet(String sheetName, int rows, int columns)
            throws IllegalArgumentException {
        if (sheetName != null && sheetName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Sheet Name cannot be an empty String.");
        }
        if (sheetName != null && sheetName.length() > 31) {
            throw new IllegalArgumentException(
                    "Sheet Name cannot be longer than 31 characters");
        }
        if (sheetName != null && isSheetNameExisting(sheetName)) {
            throw new IllegalArgumentException(
                    "Sheet name must be unique within the workbook.");
        }
        final Sheet previousSheet = getActiveSheet();
        SpreadsheetFactory
                .addNewSheet(this, workbook, sheetName, rows, columns);
        fireSelectedSheetChangeEvent(previousSheet, getActiveSheet());
    }

    /**
     * Deletes the sheet with the given POI model index.
     * 
     * Note: A workbook must contain at least one visible sheet.
     * 
     * @param poiSheetIndex
     *            POI model index of the sheet to delete, 0-based, max value
     *            {@link Workbook#getNumberOfSheets()} -1.
     * @throws IllegalArgumentException
     *             In case there is only one visible sheet, or if the index is
     *             invalid.
     */
    public void deleteSheetWithPOIIndex(int poiSheetIndex)
            throws IllegalArgumentException {
        if (getNumberOfVisibleSheets() < 2) {
            throw new IllegalArgumentException(
                    "A workbook must contain at least one visible worksheet");
        }
        int removedVisibleIndex = getSpreadsheetSheetIndex(poiSheetIndex);
        workbook.removeSheetAt(poiSheetIndex);

        // POI doesn't seem to shift the active sheet index ...
        int oldIndex = getState().sheetIndex - 1;
        if (removedVisibleIndex <= oldIndex) { // removed before current
            if (oldIndex == (getNumberOfVisibleSheets())) {
                // need to shift index backwards if the current sheet is last
                workbook.setActiveSheet(getVisibleSheetPOIIndex(oldIndex - 1));
            } else {
                workbook.setActiveSheet(getVisibleSheetPOIIndex(oldIndex));
            }
        }
        // need to reload everything because there is a ALWAYS chance that the
        // removed sheet effects the currently visible sheet (via cell formulas
        // etc.)
        reloadActiveSheetData();
    }

    /**
     * Deletes the sheet at the given index.
     * 
     * Note: A workbook must contain at least one visible sheet.
     * 
     * @param sheetIndex
     *            Index of the sheet to delete among the visible sheets,
     *            0-based, maximum value {@link #getNumberOfVisibleSheets()} -1.
     * @throws IllegalArgumentException
     *             In case there is only one visible sheet, or if the given
     *             index is invalid.
     */
    public void deleteSheet(int sheetIndex) throws IllegalArgumentException {
        if (getNumberOfVisibleSheets() < 2) {
            throw new IllegalArgumentException(
                    "A workbook must contain at least one visible worksheet");
        }
        deleteSheetWithPOIIndex(getVisibleSheetPOIIndex(sheetIndex));
    }

    /**
     * Returns the number of currently visible sheets in the component. Doesn't
     * include the hidden or very hidden sheets in the POI model.
     * 
     * @return Number of visible sheets.
     */
    public int getNumberOfVisibleSheets() {
        if (getState().sheetNames != null) {
            return getState().sheetNames.length;
        } else {
            return 0;
        }
    }

    /**
     * Returns the total number of sheets in the workbook (includes hidden and
     * very hidden sheets).
     * 
     * @return Total number of sheets in the workbook
     */
    public int getNumberOfSheets() {
        return workbook.getNumberOfSheets();
    }

    private boolean isSheetNameExisting(String sheetName) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (workbook.getSheetName(i).equals(sheetName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the index of the currently active sheet among the visible sheets
     * ( hidden or very hidden sheets not included).
     * 
     * @return Index of the active sheet, 0-based
     */
    public int getActiveSheetIndex() {
        return getState(false).sheetIndex - 1;
    }

    /**
     * Returns the POI model index of the currently active sheet (index among
     * all sheets including hidden and very hidden sheets).
     * 
     * @return POI model index of the active sheet, 0-based
     */
    public int getActiveSheetPOIIndex() {
        return getVisibleSheetPOIIndex(getState(false).sheetIndex - 1);
    }

    /**
     * Sets the currently active sheet within the sheets that are visible.
     * 
     * @param sheetIndex
     *            Index of the target sheet (among the visible sheets), 0-based
     * @throws IllegalArgumentException
     *             If the index is invalid
     */
    public void setActiveSheetIndex(int sheetIndex)
            throws IllegalArgumentException {
        if (sheetIndex < 0 || sheetIndex >= getState().sheetNames.length) {
            throw new IllegalArgumentException("Invalid Sheet index given.");
        }
        int POISheetIndex = getVisibleSheetPOIIndex(sheetIndex);
        setActiveSheetWithPOIIndex(POISheetIndex);
    }

    /**
     * Sets the currently active sheet. The sheet at the given index should be
     * visible (not hidden or very hidden).
     * 
     * @param sheetIndex
     *            Index of sheet in the POI model (contains all sheets), 0-based
     * @throws IllegalArgumentException
     *             If the index is invalid, or if the sheet at the given index
     *             is hidden or very hidden.
     */
    public void setActiveSheetWithPOIIndex(int sheetIndex)
            throws IllegalArgumentException {
        if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
            throw new IllegalArgumentException("Invalid POI Sheet index given.");
        }
        if (workbook.isSheetHidden(sheetIndex)
                || workbook.isSheetVeryHidden(sheetIndex)) {
            throw new IllegalArgumentException(
                    "Cannot set a hidden or very hidden sheet as the active sheet. Given index: "
                            + sheetIndex);
        }
        workbook.setActiveSheet(sheetIndex);
        reloadActiveSheetData();
        SpreadsheetFactory.reloadSpreadsheetData(this,
                workbook.getSheetAt(sheetIndex));
        reloadActiveSheetStyles();
    }

    /**
     * This method will be called when a selected sheet change is requested.
     * 
     * @param tabIndex
     *            Index of the sheet to select.
     * @param scrollLeft
     *            Current horizontal scroll position
     * @param scrollTop
     *            Current vertical scroll position
     */
    protected void onSheetSelected(int tabIndex, int scrollLeft, int scrollTop) {
        // this is for the very rare occasion when the sheet has been
        // selected and the selected sheet value is still negative
        int oldIndex = Math.abs(getState().sheetIndex) - 1;
        getState().verticalScrollPositions[oldIndex] = scrollTop;
        getState().horizontalScrollPositions[oldIndex] = scrollLeft;
        Sheet oldSheet = getActiveSheet();
        setActiveSheetIndex(tabIndex);
        Sheet newSheet = getActiveSheet();
        fireSelectedSheetChangeEvent(oldSheet, newSheet);
    }

    /**
     * This method is called when the creation of a new sheet has been
     * requested.
     * 
     * @param scrollLeft
     *            Current horizontal scroll position
     * @param scrollTop
     *            Current vertical scroll position
     */
    protected void onNewSheetCreated(int scrollLeft, int scrollTop) {
        getState().verticalScrollPositions[getState().sheetIndex - 1] = scrollTop;
        getState().horizontalScrollPositions[getState().sheetIndex - 1] = scrollLeft;
        createNewSheet(null, defaultNewSheetRows, defaultNewSheetColumns);
    }

    /**
     * This method is called when a request to rename a sheet has been made.
     * 
     * @param sheetIndex
     *            Index of the sheet to rename (among visible sheets).
     * @param sheetName
     *            New name for the sheet.
     */
    protected void onSheetRename(int sheetIndex, String sheetName) {
        // if excel doesn't keep these in history, neither will we
        setSheetNameWithPOIIndex(getVisibleSheetPOIIndex(sheetIndex), sheetName);
    }

    /**
     * Get the number of columns in the currently active sheet, or if
     * {@link #setMaxColumns(int)} has been used, the current number of columns
     * the component shows (not the amount of columns in the actual sheet in the
     * POI model).
     * 
     * @return Number of visible columns.
     */
    public int getColumns() {
        return getState().cols;
    }

    /**
     * Get the number of rows in the currently active sheet, or if
     * {@link #setMaxRows(int)} has been used, the current number of rows the
     * component shows (not the amount of rows in the actual sheet in the POI
     * model).
     * 
     * @return Number of visible rows.
     */
    public int getRows() {
        return getState().rows;
    }

    /**
     * Gets the current DataFormatter.
     * 
     * @return The data formatter for this Spreadsheet.
     */
    public DataFormatter getDataFormatter() {
        return valueManager.getDataFormatter();
    }

    /**
     * Gets the Cell at the given address. If the cell is updated in outside
     * code, call {@link #refreshCells(Cell...)} AFTER ALL UPDATES (value, type,
     * formatting or style) to mark the cell as "dirty".
     * 
     * @param cellAddress
     *            Address of the target Cell, e.g. "A3"
     * @return The cell at the given address, or null if not defined
     */
    public Cell getCell(String cellAddress) {
        CellReference ref = new CellReference(cellAddress);
        Row r = workbook.getSheetAt(workbook.getActiveSheetIndex()).getRow(
                ref.getRow());
        if (r != null) {
            return r.getCell(ref.getCol());
        } else {
            return null;
        }
    }

    /**
     * Gets the Cell at the given coordinated. If the cell is updated in outside
     * code, call {@link #refreshCells(Cell...)} AFTER ALL UPDATES (value, type,
     * formatting or style) to mark the cell as "dirty".
     * 
     * @param row
     *            Row index of the cell to update, 0-based
     * @param col
     *            Column index of the cell to update, 0-based
     * @return The cell at the given coordinates, or null if not defined
     */
    public Cell getCell(int row, int col) {
        Row r = workbook.getSheetAt(workbook.getActiveSheetIndex()).getRow(row);
        if (r != null) {
            return r.getCell(col);
        } else {
            return null;
        }
    }

    /**
     * Deletes the cell from the sheet and the underlying POI model as well.
     * This really deletes the cell, instead of just making it's value blank.
     * 
     * @param row
     *            Row index of the cell to delete, 0-based
     * @param col
     *            Column index of the cell to delete, 0-based
     */
    public void deleteCell(int row, int col) {
        final Sheet activeSheet = workbook.getSheetAt(workbook
                .getActiveSheetIndex());
        final Cell cell = activeSheet.getRow(row).getCell(col);
        if (cell != null) {
            // cell.setCellStyle(null); // TODO NPE on HSSF
            styler.cellStyleUpdated(cell, true);
            activeSheet.getRow(row).removeCell(cell);
            valueManager.cellDeleted(cell);
            refreshCells(cell);
        }
    }

    /**
     * Refreshes the given cell(s). Should be called when the cell
     * value/formatting/style/etc. updating is done.
     * 
     * NOTE: For optimal performance temporarily collect your updated cells and
     * call this method only once per update cycle. Calling this method
     * repeatedly for individual cells is not a good idea.
     * 
     * @param cells
     *            Cell(s) to update
     */
    public void refreshCells(Cell... cells) {
        if (cells != null) {
            for (Cell cell : cells) {
                markCellAsUpdated(cell, true);
            }
            updateMarkedCells();
        }
    }

    /**
     * Refreshes the given cell(s). Should be called when the cell
     * value/formatting/style/etc. updating is done.
     * 
     * NOTE: For optimal performance temporarily collect your updated cells and
     * call this method only once per update cycle. Calling this method
     * repeatedly for individual cells is not a good idea.
     * 
     * @param cells
     *            A Collection of Cells to update
     */
    public void refreshCells(Collection<Cell> cells) {
        if (cells != null && !cells.isEmpty()) {
            for (Cell cell : cells) {
                markCellAsUpdated(cell, true);
            }
            updateMarkedCells();
        }
    }

    /**
     * Marks the cell as updated. Should be called when the cell
     * value/formatting/style/etc. updating is done.
     * 
     * @param cellStyleUpdated
     *            True if the cell style has changed
     * 
     * @param cell
     *            The updated cell
     */
    void markCellAsUpdated(Cell cell, boolean cellStyleUpdated) {
        valueManager.cellUpdated(cell);
        if (cellStyleUpdated) {
            styler.cellStyleUpdated(cell, true);
        }
    }

    /**
     * Marks the cell as deleted. This method should be called after removing a
     * cell from the {@link Workbook} using POI API.
     * 
     * @param cellStyleUpdated
     *            True if the cell style has changed
     * @param cell
     *            The cell that has been deleted.
     */
    public void markCellAsDeleted(Cell cell, boolean cellStyleUpdated) {
        valueManager.cellDeleted(cell);
        if (cellStyleUpdated) {
            styler.cellStyleUpdated(cell, true);
        }
        refreshCells(cell);
    }

    /**
     * Updates the content of the cells that have been marked for update with
     * {@link #markCellAsUpdated(Cell, boolean)}.
     * <p>
     * Does NOT update custom components (editors / always visible) for the
     * cells. For that, use {@link #reloadVisibleCellContents()}
     */
    void updateMarkedCells() {
        // update conditional formatting in case styling has changed. New values
        // are fetched in ValueManager (below).
        conditionalFormatter.createConditionalFormatterRules();
        // FIXME should be optimized, should not go through all links, comments
        // etc. always
        valueManager.updateMarkedCellValues();
        // if the selected cell is of type formula, there is a change that the
        // formula has been changed.
        selectionManager.reSelectSelectedCell();
        // Update the cell comments as well to show them instantly after adding
        // them
        loadCellComments();
    }

    /**
     * Creates a new Formula type cell with the given formula.
     * 
     * After all editing is done, call {@link #refreshCells(Cell...)()} or
     * {@link #refreshAllCellValues()} to make sure client side is updated.
     * 
     * @param row
     *            Row index of the new cell, 0-based
     * @param col
     *            Column index of the new cell, 0-based
     * @param formula
     *            The formula to set to the new cell (should NOT start with "=")
     * @return The newly created cell
     * @throws IllegalArgumentException
     *             If columnIndex < 0 or greater than the maximum number of
     *             supported columns (255 for *.xls, 1048576 for *.xlsx)
     */
    public Cell createFormulaCell(int row, int col, String formula)
            throws IllegalArgumentException {
        final Sheet activeSheet = workbook.getSheetAt(workbook
                .getActiveSheetIndex());
        Row r = activeSheet.getRow(row);
        if (r == null) {
            r = activeSheet.createRow(row);
        }
        Cell cell = r.getCell(col);
        if (cell == null) {
            cell = r.createCell(col, Cell.CELL_TYPE_FORMULA);
        } else {
            final String key = SpreadsheetUtil.toKey(col + 1, row + 1);
            valueManager.clearCellCache(key);
            cell.setCellType(Cell.CELL_TYPE_FORMULA);
        }
        cell.setCellFormula(formula);
        valueManager.cellUpdated(cell);
        return cell;
    }

    /**
     * Create a new cell (or replace existing) with the given value, the type of
     * the value parameter will define the type of the cell. The value may be of
     * the following types: Boolean, Calendar, Date, Double or String. The
     * default type will be String, value of ({@link #toString()} will be given
     * as the cell value.
     * 
     * For formula cells, use {@link #createFormulaCell(int, int, String)}.
     * 
     * After all editing is done, call {@link #refreshCells(Cell...)} or
     * {@link #refreshAllCellValues()} to make sure the client side is updated.
     * 
     * @param row
     *            Row index of the new cell, 0-based
     * @param col
     *            Column index of the new cell, 0-based
     * @param value
     *            Object representing the type and value of the Cell
     * @return The newly created cell
     * @throws IllegalArgumentException
     *             If columnIndex < 0 or greater than the maximum number of
     *             supported columns (255 for *.xls, 1048576 for *.xlsx)
     */
    public Cell createCell(int row, int col, Object value)
            throws IllegalArgumentException {
        final Sheet activeSheet = workbook.getSheetAt(workbook
                .getActiveSheetIndex());
        Row r = activeSheet.getRow(row);
        if (r == null) {
            r = activeSheet.createRow(row);
        }
        Cell cell = r.getCell(col);
        if (cell == null) {
            cell = r.createCell(col);
        } else {
            final String key = SpreadsheetUtil.toKey(col + 1, row + 1);
            valueManager.clearCellCache(key);
        }
        if (value instanceof Double) {
            cell.setCellValue((Double) value);
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else if (value instanceof Date) {
            cell.setCellValue((Date) value);
        } else if (value instanceof Calendar) {
            cell.setCellValue((Calendar) value);
        } else {
            cell.setCellValue(value.toString());
        }
        valueManager.cellUpdated(cell);
        return cell;
    }

    /**
     * Forces recalculation and update to the client side for values of all of
     * the sheet's cells.
     * 
     * Note: DOES NOT UPDATE STYLES; use {@link #refreshCells(Cell...)} when
     * cell styles change.
     */
    public void refreshAllCellValues() {
        valueManager.clearEvaluatorCache();
        valueManager.clearCachedContent();
        updateRowAndColumnRangeCellData(1, 1, getRows(), getColumns());
        // if the selected cell is of type formula, there is a change that the
        // formula has been changed.
        selectionManager.reSelectSelectedCell();
    }

    /**
     * Set the number of columns shown for the current sheet. Any null cells are
     * left empty. Any cells outside the given columns are hidden. Does not
     * update the actual POI-based model!
     * 
     * The default value will be the actual size of the sheet from the POI
     * model.
     * 
     * @param cols
     *            New maximum column count.
     */
    public void setMaxColumns(int cols) {
        if (getState().cols != cols) {
            getState().cols = cols;
        }
    }

    /**
     * Set the number of rows shown for the current sheet. Any null cells are
     * left empty. Any cells outside the given rows are hidden. Does not update
     * the actual POI-based model!
     * 
     * The default value will be the actual size of the sheet from the POI
     * model.
     * 
     * @param rows
     *            New maximum row count.
     */
    public void setMaxRows(int rows) {
        if (getState().rows != rows) {
            getState().rows = rows;
        }
    }

    /**
     * Does {@link #setMaxColumns(int)} & {@link #setMaxRows(int)} in one
     * method.
     * 
     * @param rows
     *            Maximum row count
     * @param cols
     *            Maximum column count
     */
    public void setSheetMaxSize(int rows, int cols) {
        getState().cols = cols;
        getState().rows = rows;
    }

    /**
     * Gets the default column width for the currently active sheet. This is
     * derived from the active sheet's ({@link #getActiveSheet()}) default
     * column width (Sheet {@link #getDefaultColumnWidth()}).
     * 
     * @return The default column width in PX
     */
    public int getDefaultColumnWidth() {
        return getState().defColW;
    }

    /**
     * Sets the default column width in pixels that the component uses, this
     * doesn't change the default column width of the underlying sheet, returned
     * by {@link #getActiveSheet()} and {@link Sheet#getDefaultColumnWidth()}.
     * 
     * @param widthPX
     *            The default column width in pixels
     */
    public void setDefaultColumnWidth(int widthPX) {
        if (widthPX <= 0) {
            throw new IllegalArgumentException(
                    "Default column width must be over 0, given value: "
                            + widthPX);
        }
        getState().defColW = widthPX;
        defaultColWidthSet = true;
    }

    /**
     * Gets the default row height in points. By default it should be the same
     * as {@link Sheet#getDefaultRowHeightInPoints()} for the currently active
     * sheet {@link #getActiveSheet()}.
     * 
     * @return Default row height for the currently active sheet, in points.
     */
    public float getDefaultRowHeight() {
        return getState().defRowH;
    }

    /**
     * Sets the default row height in points for this Spreadsheet and the
     * currently active sheet, returned by {@link #getActiveSheet()}.
     * 
     * @param heightPT
     *            New default row height in points.
     */
    public void setDefaultRowHeight(float heightPT) {
        if (heightPT <= 0.0f) {
            throw new IllegalArgumentException(
                    "Default row height must be over 0, given value: "
                            + heightPT);
        }
        getActiveSheet().setDefaultRowHeightInPoints(heightPT);
        getState().defRowH = heightPT;
        defaultRowHeightSet = true;
    }

    /**
     * This method is called when column auto-fit has been initiated from the
     * browser by double-clicking the border of the target column header.
     * 
     * @param columnIndex
     *            Index of the target column, 0-based
     */
    protected void onColumnAutofit(int columnIndex) {
        SizeChangeCommand command = new SizeChangeCommand(this, Type.COLUMN);
        command.captureValues(new Integer[] { columnIndex + 1 });
        autofitColumn(columnIndex);
        historyManager.addCommand(command);
    }

    /**
     * Sets the column to automatically adjust the column width to fit the
     * largest cell content within the column. This is a POI feature, and is
     * meant to be called after all the data for the target column has been
     * written. See {@link Sheet#autoSizeColumn(int)}.
     * <p>
     * This does not take into account cells that have custom Vaadin components
     * inside them.
     * 
     * @param columnIndex
     *            Index of the target column, 0-based
     */
    public void autofitColumn(int columnIndex) {
        final Sheet activeSheet = getActiveSheet();
        activeSheet.autoSizeColumn(columnIndex);
        getState().colW[columnIndex] = ExcelToHtmlUtils
                .getColumnWidthInPx(activeSheet.getColumnWidth(columnIndex));
        getCellValueManager().clearCacheForColumn(columnIndex + 1);
        getCellValueManager().loadCellData(firstRow, columnIndex + 1, lastRow,
                columnIndex + 1);
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
            loadImages();
        }
    }

    /**
     * Shifts rows between startRow and endRow n number of rows. If you use a
     * negative number for n, the rows will be shifted upwards. This method
     * ensures that rows can't wrap around.
     * <p>
     * If you are adding / deleting rows, you might want to change the number of
     * visible rows rendered {@link #getRows()} with {@link #setMaxRows(int)}.
     * <p>
     * See {@link Sheet#shiftRows(int, int, int)}.
     * 
     * @param startRow
     *            The first row to shift, 0-based
     * @param endRow
     *            The last row to shift, 0-based
     * @param n
     *            Number of rows to shift, positive numbers shift down, negative
     *            numbers shift up.
     */
    public void shiftRows(int startRow, int endRow, int n) {
        shiftRows(startRow, endRow, n, false, false);
    }

    /**
     * Shifts rows between startRow and endRow n number of rows. If you use a
     * negative number for n, the rows will be shifted upwards. This method
     * ensures that rows can't wrap around.
     * <p>
     * If you are adding / deleting rows, you might want to change the number of
     * visible rows rendered {@link #getRows()} with {@link #setMaxRows(int)}.
     * <p>
     * See {@link Sheet#shiftRows(int, int, int, boolean, boolean)}.
     * 
     * @param startRow
     *            The first row to shift, 0-based
     * @param endRow
     *            The last row to shift, 0-based
     * @param n
     *            Number of rows to shift, positive numbers shift down, negative
     *            numbers shift up.
     * @param copyRowHeight
     *            True to copy the row height during the shift
     * @param resetOriginalRowHeight
     *            True to set the original row's height to the default
     */
    public void shiftRows(int startRow, int endRow, int n,
            boolean copyRowHeight, boolean resetOriginalRowHeight) {
        Sheet sheet = getActiveSheet();
        sheet.shiftRows(startRow, endRow, n, copyRowHeight,
                resetOriginalRowHeight);
        // need to re-send the cell values to client
        // remove all cached cell data that is now empty
        int start = n < 0 ? endRow + n + 1 : startRow;
        int end = n < 0 ? endRow : startRow + n - 1;
        valueManager.updateDeletedRowsInClientCache(start, end);
        // updateDeletedRowsInClientCache(start + 1, end + 1); this was a bug?
        int firstEffectedRow = n < 0 ? startRow + n : startRow;
        int lastEffectedRow = n < 0 ? endRow : endRow + n;
        if (copyRowHeight || resetOriginalRowHeight) {
            // might need to increase the size of the row heights array
            int oldLength = getState(false).rowH.length;
            int neededLength = endRow + n + 1;
            if (n > 0 && oldLength < neededLength) {
                getState().rowH = Arrays.copyOf(getState().rowH, neededLength);
            }
            for (int i = firstEffectedRow; i <= lastEffectedRow; i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    if (row.getZeroHeight()) {
                        getState().rowH[i] = 0f;
                    } else {
                        getState().rowH[i] = row.getHeightInPoints();
                    }
                } else {
                    getState().rowH[i] = sheet.getDefaultRowHeightInPoints();
                }
            }
        }
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
        }
        // need to shift the cell styles, clear and update
        // need to go -1 and +1 because of shifted borders..
        final ArrayList<Cell> cellsToUpdate = new ArrayList<Cell>();
        for (int r = (firstEffectedRow - 1); r <= (lastEffectedRow + 1); r++) {
            if (r < 0) {
                r = 0;
            }
            Row row = sheet.getRow(r);
            final Integer rowIndex = new Integer(r + 1);
            if (row == null) {
                if (getState(false).hiddenRowIndexes.contains(rowIndex)) {
                    getState().hiddenRowIndexes.remove(rowIndex);
                }
                for (int c = 0; c < getState().cols; c++) {
                    styler.clearCellStyle(r, c);
                }
            } else {
                if (row.getZeroHeight()) {
                    getState().hiddenRowIndexes.add(rowIndex);
                } else if (getState(false).hiddenRowIndexes.contains(rowIndex)) {
                    getState().hiddenRowIndexes.remove(rowIndex);
                }
                for (int c = 0; c < getState().cols; c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) {
                        styler.clearCellStyle(r, c);
                    } else {
                        cellsToUpdate.add(cell);
                    }
                }
            }
        }
        for (Cell cell : cellsToUpdate) {
            styler.cellStyleUpdated(cell, false);
        }
        styler.loadCustomBorderStylesToState();

        updateMarkedCells(); // deleted and formula cells and style selectors
        updateRowAndColumnRangeCellData(firstRow, firstColumn, lastRow,
                lastColumn); // shifted area values

        CellReference selectedCellReference = selectionManager
                .getSelectedCellReference();
        if (selectedCellReference.getRow() >= firstEffectedRow
                && selectedCellReference.getRow() <= lastEffectedRow) {
            selectionManager.onSheetAddressChanged(selectedCellReference
                    .formatAsString());
        }
    }

    private void updateMergedRegions() {
        int regions = getActiveSheet().getNumMergedRegions();
        if (regions > 0) {
            getState().mergedRegions = new ArrayList<MergedRegion>();
            for (int i = 0; i < regions; i++) {
                final CellRangeAddress region = getActiveSheet()
                        .getMergedRegion(i);
                try {
                    final MergedRegion mergedRegion = new MergedRegion();
                    mergedRegion.col1 = region.getFirstColumn() + 1;
                    mergedRegion.col2 = region.getLastColumn() + 1;
                    mergedRegion.row1 = region.getFirstRow() + 1;
                    mergedRegion.row2 = region.getLastRow() + 1;
                    mergedRegion.id = mergedRegionCounter++;
                    getState().mergedRegions.add(i, mergedRegion);
                } catch (IndexOutOfBoundsException ioobe) {
                    createMergedRegionIntoSheet(region);
                }
            }
            while (regions < getState(false).mergedRegions.size()) {
                getState().mergedRegions.remove(getState(false).mergedRegions
                        .size() - 1);
            }
        } else {
            getState().mergedRegions = null;
        }
    }

    /**
     * Deletes rows. See {@link Sheet#removeRow(Row)}. Removes all row content,
     * deletes cells and resets the sheet size.
     * 
     * Does not shift rows up (!) - use
     * {@link #shiftRows(int, int, int, boolean, boolean)} for that.
     * 
     * @param startRow
     *            Index of the starting row, 0-based
     * @param endRow
     *            Index of the ending row, 0-based
     */
    public void deleteRows(int startRow, int endRow) {
        Sheet sheet = getActiveSheet();
        for (int i = startRow; i <= endRow; i++) {
            Row row = sheet.getRow(i);
            if (row != null) {
                getActiveSheet().removeRow(row);
            }
        }
        for (int i = startRow; i <= endRow; i++) {
            getState(false).rowH[i] = sheet.getDefaultRowHeightInPoints();
        }
        updateMergedRegions();
        valueManager.updateDeletedRowsInClientCache(startRow + 1, endRow + 1);
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
        }
        updateMarkedCells();
        CellReference selectedCellReference = getSelectedCellReference();
        if (selectedCellReference.getRow() >= startRow
                && selectedCellReference.getRow() <= endRow) {
            selectionManager.reSelectSelectedCell();
        }

    }

    /**
     * Merges cells. See {@link Sheet#addMergedRegion(CellRangeAddress)}.
     * 
     * @param selectionRange
     *            The cell range to merge, e.g. "B3:C5"
     */
    public void addMergedRegion(String selectionRange) {
        addMergedRegion(CellRangeAddress.valueOf(selectionRange));
    }

    /**
     * Merge cells. See {@link Sheet#addMergedRegion(CellRangeAddress)}.
     * 
     * @param row1
     *            Index of the starting row of the merged region, 0-based
     * @param col1
     *            Index of the starting column of the merged region, 0-based
     * @param row2
     *            Index of the ending row of the merged region, 0-based
     * @param col2
     *            Index of the ending column of the merged region, 0-based
     */
    public void addMergedRegion(int row1, int col1, int row2, int col2) {
        addMergedRegion(new CellRangeAddress(row1, row2, col1, col2));
    }

    /**
     * Merges the given cells. See
     * {@link Sheet#addMergedRegion(CellRangeAddress)}.
     * <p>
     * If another existing merged region is completely inside the given range,
     * it is removed. If another existing region either encloses or overlaps the
     * given range, an error is thrown. See
     * {@link CellRangeUtil#intersect(CellRangeAddress, CellRangeAddress)}.
     * <p>
     * Note: POI doesn't seem to update the cells that are "removed" due to the
     * merge - the values for those cells still exist and continue being used in
     * possible formulas. If you need to make sure those values are removed,
     * just delete the cells before creating the merged region.
     * <p>
     * If the added region affects the currently selected cell, a new
     * {@link SelectionChangeEvent} is fired.
     * 
     * @param region
     *            The range of cells to merge
     * @throws IllegalArgumentException
     *             If the given region overlaps with or encloses another
     *             existing region within the sheet.
     */
    public void addMergedRegion(CellRangeAddress region)
            throws IllegalArgumentException {
        final Sheet sheet = getActiveSheet();
        // need to check if there are merged regions already inside the given
        // range, otherwise very bad inconsistencies appear.
        int index = 0;
        while (index < sheet.getNumMergedRegions()) {
            CellRangeAddress existingRegion = sheet.getMergedRegion(index);
            int intersect = CellRangeUtil.intersect(region, existingRegion);
            if (intersect == CellRangeUtil.INSIDE) {
                deleteMergedRegion(index);
            } else if (intersect == CellRangeUtil.OVERLAP
                    || intersect == CellRangeUtil.ENCLOSES) {
                throw new IllegalArgumentException("An existing region "
                        + existingRegion
                        + " "
                        + (intersect == CellRangeUtil.OVERLAP ? "overlaps "
                                : "encloses ") + "the given region " + region);
            } else {
                index++;
            }
        }
        createMergedRegionIntoSheet(region);
        selectionManager.mergedRegionAdded(region);
    }

    private void createMergedRegionIntoSheet(CellRangeAddress region) {
        Sheet sheet = getActiveSheet();
        int addMergedRegionIndex = sheet.addMergedRegion(region);
        MergedRegion mergedRegion = new MergedRegion();
        mergedRegion.col1 = region.getFirstColumn() + 1;
        mergedRegion.col2 = region.getLastColumn() + 1;
        mergedRegion.row1 = region.getFirstRow() + 1;
        mergedRegion.row2 = region.getLastRow() + 1;
        mergedRegion.id = mergedRegionCounter++;
        if (getState().mergedRegions == null) {
            getState().mergedRegions = new ArrayList<MergedRegion>();
        }
        getState().mergedRegions.add(addMergedRegionIndex - 1, mergedRegion);
        // update the style & data for the region cells, effects region + 1
        // FIXME POI doesn't seem to care that the other cells inside the merged
        // region should be removed; the values those cells have are still used
        // in formulas..
        for (int r = mergedRegion.row1; r <= (mergedRegion.row2 + 1); r++) {
            Row row = sheet.getRow(r - 1);
            for (int c = mergedRegion.col1; c <= (mergedRegion.col2 + 1); c++) {
                if (row != null) {
                    Cell cell = row.getCell(c - 1);
                    if (cell != null) {
                        styler.cellStyleUpdated(cell, false);
                        if ((c != mergedRegion.col1 || r != mergedRegion.row1)
                                && c <= mergedRegion.col2
                                && r <= mergedRegion.row2) {
                            getCellValueManager().markCellForRemove(cell);
                        }
                    }
                }
            }
        }
        styler.loadCustomBorderStylesToState();
        updateMarkedCells();
    }

    /**
     * Removes a merged region with the given index. Current merged regions can
     * be inspected within the currently active sheet with
     * {@link #getActiveSheet()} and {@link Sheet#getMergedRegion(int)} and
     * {@link Sheet#getNumMergedRegions()}.
     * <p>
     * Note that in POI after removing a merged region at index n, all regions
     * added after the removed region will get a new index (index-1).
     * <p>
     * If the removed region affects the currently selected cell, a new
     * {@link SelectionChangeEvent} is fired.
     * 
     * @param index
     *            Position of the target merged region in the POI merged region
     *            array, 0-based
     */
    public void removeMergedRegion(int index) {
        final CellRangeAddress removedRegion = getActiveSheet()
                .getMergedRegion(index);
        deleteMergedRegion(index);
        updateMarkedCells();
        // update selection if removed region overlaps
        selectionManager.mergedRegionRemoved(removedRegion);
    }

    private void deleteMergedRegion(int index) {
        final Sheet sheet = getActiveSheet();
        sheet.removeMergedRegion(index);
        MergedRegion mergedRegion = getState().mergedRegions.remove(index);
        // update the style for the region cells, effects region + 1 row&col
        for (int r = mergedRegion.row1; r <= (mergedRegion.row2 + 1); r++) {
            Row row = sheet.getRow(r - 1);
            if (row != null) {
                for (int c = mergedRegion.col1; c <= (mergedRegion.col2 + 1); c++) {
                    Cell cell = row.getCell(c - 1);
                    if (cell != null) {
                        styler.cellStyleUpdated(cell, false);
                        valueManager.markCellForUpdate(cell);
                    } else {
                        styler.clearCellStyle(r, c);
                    }
                }
            }
        }
        styler.loadCustomBorderStylesToState();
    }

    /**
     * Discards all current merged regions for the sheet and reloads them from
     * the POI model.
     * <p>
     * This can be used if you want to add / remove multiple merged regions
     * directly from the POI model and need to update the component.
     * 
     * Note that you must also make sure that possible styles for the merged
     * regions are updated, if those were modified, by calling
     * {@link #reloadActiveSheetStyles()}.
     */
    public void reloadAllMergedRegions() {
        SpreadsheetFactory.loadMergedRegions(this);
    }

    /**
     * Reloads all the styles for the currently active sheet.
     */
    public void reloadActiveSheetStyles() {
        styler.reloadActiveSheetCellStyles();
    }

    /**
     * Hides or shows the given column, see
     * {@link Sheet#setColumnHidden(int, boolean)}.
     * 
     * @param columnIndex
     *            Index of the target column, 0-based
     * @param hidden
     *            True to hide the target column, false to show it.
     */
    public void setColumnHidden(int columnIndex, boolean hidden) {
        getActiveSheet().setColumnHidden(columnIndex, hidden);
        if (hidden && !getState().hiddenColumnIndexes.contains(columnIndex + 1)) {
            getState().hiddenColumnIndexes.add(columnIndex + 1);
            getState().colW[columnIndex] = 0;
        } else if (!hidden
                && getState().hiddenColumnIndexes.contains(columnIndex + 1)) {
            getState().hiddenColumnIndexes
                    .remove(getState().hiddenColumnIndexes
                            .indexOf(columnIndex + 1));
            getState().colW[columnIndex] = ExcelToHtmlUtils
                    .getColumnWidthInPx(getActiveSheet().getColumnWidth(
                            columnIndex));
            getCellValueManager().clearCacheForColumn(columnIndex + 1);
            getCellValueManager().loadCellData(firstRow, columnIndex + 1,
                    lastRow, columnIndex + 1);
        }
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
            loadImages();
        }
    }

    /**
     * Gets the visibility state of the given column. See
     * {@link Sheet#isColumnHidden(int)}.
     * 
     * @param columnIndex
     *            Index of the target column, 0-based
     * @return true if the target column is hidden, false if it is visible.
     */
    public boolean isColumnHidden(int columnIndex) {
        return getActiveSheet().isColumnHidden(columnIndex);
    }

    /**
     * Hides or shows the given row, see {@link Row#setZeroHeight(boolean)}.
     * 
     * @param rowIndex
     *            Index of the target row, 0-based
     * @param hidden
     *            True to hide the target row, false to show it.
     */
    public void setRowHidden(int rowIndex, boolean hidden) {
        final Sheet activeSheet = getActiveSheet();
        Row row = activeSheet.getRow(rowIndex);
        if (row == null) {
            row = activeSheet.createRow(rowIndex);
        }
        row.setZeroHeight(hidden);
        if (hidden && !getState().hiddenRowIndexes.contains(rowIndex + 1)) {
            getState().hiddenRowIndexes.add(rowIndex + 1);
            getState().rowH[rowIndex] = 0.0F;
        } else if (!hidden
                && getState().hiddenRowIndexes.contains(rowIndex + 1)) {
            getState().hiddenRowIndexes.remove(getState().hiddenRowIndexes
                    .indexOf(rowIndex + 1));
            getState().rowH[rowIndex] = row.getHeightInPoints();
        }
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
            loadImages();
        }
    }

    /**
     * Gets the visibility state of the given row. A row is hidden when it has
     * zero height, see {@link Row#getZeroHeight()}.
     * 
     * @param rowIndex
     *            Index of the target row, 0-based
     * @return true if the target row is hidden, false if it is visible.
     */
    public boolean isRowHidden(int rowIndex) {
        Row row = getActiveSheet().getRow(rowIndex);
        return row == null ? false : row.getZeroHeight();
    }

    /**
     * Reinitializes the component from the given Excel file.
     * 
     * @param file
     *            Data source file. Excel format is expected.
     * @throws IOException
     *             If the file can't be read, or the file is of an invalid
     *             format.
     */
    public void read(File file) throws IOException {
        SpreadsheetFactory.reloadSpreadsheetComponent(this, file);
        srcUri = file.toURI().toString();
    }

    /**
     * Reinitializes the component from the given input stream. The expected
     * format is that of an Excel file.
     * 
     * @param inputStream
     *            Data source input stream. Excel format is expected.
     * @throws IOException
     *             If handling the stream fails, or the data is in an invalid
     *             format.
     */
    public void read(InputStream inputStream) throws IOException {
        SpreadsheetFactory.reloadSpreadsheetComponent(this, inputStream);
        srcUri = null;
    }

    /**
     * Exports current spreadsheet into a File with the given name.
     * 
     * @param fileName
     *            The full name of the file. If the name doesn't end with '.xls'
     *            or '.xlsx', the approriate one will be appended.
     * @return A File with the content of the current {@link Workbook}, In the
     *         file format of the original {@link Workbook}.
     * @throws FileNotFoundException
     *             If file name was invalid
     * @throws IOException
     *             If the file can't be written to for any reason
     */
    public File write(String fileName) throws FileNotFoundException,
            IOException {
        return SpreadsheetFactory.write(this, fileName);
    }

    /**
     * Exports current spreadsheet as an output stream.
     * 
     * @param outputStream
     *            The target stream
     * @throws IOException
     *             If writing to the stream fails
     */
    public void write(OutputStream outputStream) throws IOException {
        SpreadsheetFactory.write(this, outputStream);
    }

    /**
     * The row buffer size determines the amount of content rendered outside the
     * top and bottom edges of the visible cell area, for smoother scrolling.
     * <p>
     * Size is in pixels, the default is 200.
     * 
     * @return The current row buffer size
     */
    public int getRowBufferSize() {
        return getState().rowBufferSize;
    }

    /**
     * Sets the row buffer size. Comes into effect the next time sheet is
     * scrolled or reloaded.
     * <p>
     * The row buffer size determines the amount of content rendered outside the
     * top and bottom edges of the visible cell area, for smoother scrolling.
     * 
     * @param rowBufferInPixels
     *            The amount of extra content rendered outside the top and
     *            bottom edges of the visible area.
     */
    public void setRowBufferSize(int rowBufferInPixels) {
        getState().rowBufferSize = rowBufferInPixels;
    }

    /**
     * The column buffer size determines the amount of content rendered outside
     * the left and right edges of the visible cell area, for smoother
     * scrolling.
     * <p>
     * Size is in pixels, the default is 200.
     * 
     * @return The current column buffer size
     */
    public int getColBufferSize() {
        return getState().columnBufferSize;
    }

    /**
     * Sets the column buffer size. Comes into effect the next time sheet is
     * scrolled or reloaded.
     * <p>
     * The column buffer size determines the amount of content rendered outside
     * the left and right edges of the visible cell area, for smoother
     * scrolling.
     * 
     * @param colBufferInPixels
     *            The amount of extra content rendered outside the left and
     *            right edges of the visible area.
     */
    public void setColBufferSize(int colBufferInPixels) {
        getState().columnBufferSize = colBufferInPixels;
    }

    /**
     * Gets the default row count for new sheets.
     * 
     * @return The default row count for new sheets.
     */
    public int getDefaultRowCount() {
        return defaultNewSheetRows;
    }

    /**
     * Sets the default row count for new sheets.
     * 
     * @param defaultRowCount
     *            The number of rows to give sheets that are created with the
     *            '+' button on the client side.
     */
    public void setDefaultRowCount(int defaultRowCount) {
        defaultNewSheetRows = defaultRowCount;
    }

    /**
     * Gets the default column count for new sheets.
     * 
     * @return The default column count for new sheets.
     */
    public int getDefaultColumnCount() {
        return defaultNewSheetColumns;
    }

    /**
     * Sets the default column count for new sheets.
     * 
     * @param defaultColumnCount
     *            The number of columns to give sheets that are created with the
     *            '+' button on the client side.
     */
    public void setDefaultColumnCount(int defaultColumnCount) {
        defaultNewSheetColumns = defaultColumnCount;
    }

    /**
     * Call this to force the spreadsheet to reload the currently viewed cell
     * contents. This forces reload of all: custom components (always visible &
     * editors) from {@link SpreadsheetComponentFactory}, hyperlinks, cells'
     * comments and cells' contents. Also updates styles for the visible area.
     */
    public void reloadVisibleCellContents() {
        loadCustomComponents();
        updateRowAndColumnRangeCellData(firstRow, firstColumn, lastRow,
                lastColumn);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.vaadin.server.AbstractClientConnector#setResource(java.lang.String,
     * com.vaadin.server.Resource)
     * 
     * Provides package visibility.
     */
    @Override
    protected void setResource(String key, Resource resource) {
        super.setResource(key, resource);
    }

    void clearSheetServerSide() {
        workbook = null;
        styler = null;

        valueManager.clearCachedContent();
        selectionManager.clear();
        historyManager.clear();

        for (SheetImageWrapper image : sheetImages) {
            setResource(image.getResourceKey(), null);
        }
        sheetImages.clear();
    }

    void setInternalWorkbook(Workbook workbook) {
        this.workbook = workbook;
        valueManager.updateEvaluator();
        styler = createSpreadsheetStyleFactory();

        reloadActiveSheetData();
        if (workbook instanceof HSSFWorkbook) {
            getState().workbookProtected = ((HSSFWorkbook) workbook)
                    .isWriteProtected();
        } else if (workbook instanceof XSSFWorkbook) {
            getState().workbookProtected = ((XSSFWorkbook) workbook)
                    .isStructureLocked();
        }
        // clear all tables from memory
        tables.clear();

        getState().verticalScrollPositions = new int[getState().sheetNames.length];
        getState().horizontalScrollPositions = new int[getState().sheetNames.length];

        conditionalFormatter = createConditionalFormatter();

        getState().workbookChangeToggle = true;
    }

    /**
     * Override this method to provide your own {@link ConditionalFormatter}
     * implementation. This method is called each time we open a workbook.
     * 
     * @return A {@link ConditionalFormatter} that is tied to this spreadsheet.
     */
    protected ConditionalFormatter createConditionalFormatter() {
        return new ConditionalFormatter(this);
    }

    /**
     * Override this method to provide your own {@link SpreadsheetStyleFactory}
     * implementation. This method is called each time we open a workbook.
     * 
     * @return A {@link SpreadsheetStyleFactory} that is tied to this
     *         Spreadsheet.
     */
    protected SpreadsheetStyleFactory createSpreadsheetStyleFactory() {
        return new SpreadsheetStyleFactory(this);
    }

    /**
     * Clears and reloads all data related to the currently active sheet.
     */
    protected void reloadActiveSheetData() {
        selectionManager.clear();
        valueManager.clearCachedContent();

        firstColumn = lastColumn = firstRow = lastRow = -1;
        for (SheetImageWrapper image : sheetImages) {
            setResource(image.getResourceKey(), null);
        }
        sheetImages.clear();
        topLeftCellCommentsLoaded = false;
        topLeftCellHyperlinksLoaded = false;

        reload = true;
        getState().sheetIndex = getSpreadsheetSheetIndex(workbook
                .getActiveSheetIndex()) + 1;
        getState().sheetProtected = getActiveSheet().getProtect();
        getState().cellKeysToEditorIdMap = null;
        getState().hyperlinksTooltips = null;
        getState().componentIDtoCellKeysMap = null;
        getState().resourceKeyToImage = null;
        getState().mergedRegions = null;
        getState().cellComments = null;
        getState().cellCommentAuthors = null;
        getState().visibleCellComments = null;
        if (customComponents != null && !customComponents.isEmpty()) {
            for (Component c : customComponents) {
                unRegisterCustomComponent(c);
            }
            customComponents.clear();
        }
        if (sheetPopupButtons != null && !sheetPopupButtons.isEmpty()) {
            for (PopupButton sf : sheetPopupButtons.values()) {
                unRegisterCustomComponent(sf);
            }
            sheetPopupButtons.clear();
        }
        // clear all tables, possible tables for new/changed sheet are added
        // after first round trip.
        tablesLoaded = false;

        reloadSheetNames();

        getState().displayGridlines = getActiveSheet().isDisplayGridlines();
        getState().displayRowColHeadings = getActiveSheet()
                .isDisplayRowColHeadings();
        markAsDirty();
    }

    /**
     * This method should be always called when the selected cell has changed so
     * proper actions can be triggered for possible custom component inside the
     * cell.
     */
    protected void loadCustomEditorOnSelectedCell() {
        CellReference selectedCellReference = selectionManager
                .getSelectedCellReference();
        if (selectedCellReference != null && customComponentFactory != null) {
            final short col = selectedCellReference.getCol();
            final int row = selectedCellReference.getRow();
            final String key = SpreadsheetUtil.toKey(col + 1, row + 1);
            Map<String, String> cellKeysToEditorIdMap = getState(false).cellKeysToEditorIdMap;
            if (cellKeysToEditorIdMap != null
                    && cellKeysToEditorIdMap.containsKey(key)
                    && customComponents != null) {
                String componentId = getState(false).cellKeysToEditorIdMap
                        .get(key);
                for (Component c : customComponents) {
                    if (c.getConnectorId().equals(componentId)) {
                        customComponentFactory.onCustomEditorDisplayed(
                                getCell(row, col), row, col, this,
                                getActiveSheet(), c);
                        return;
                    }
                }
            }
        }
    }

    private void reloadSheetNames() {
        final ArrayList<String> sheetNamesList = new ArrayList<String>();

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (!workbook.isSheetVeryHidden(i) && !workbook.isSheetHidden(i)) {
                sheetNamesList.add(workbook.getSheetName(i));
            }
        }
        getState().sheetNames = sheetNamesList
                .toArray(new String[sheetNamesList.size()]);
    }

    /**
     * Returns POI model based index for the given Spreadsheet sheet index.
     * 
     * @param visibleSheetIndex
     *            Index of the sheet within this Spreadsheet, 0-based
     * @return Index of the sheet within the POI model, or -1 if something went
     *         wrong. 0-based.
     */
    public int getVisibleSheetPOIIndex(int visibleSheetIndex) {
        int realIndex = -1;
        int i = -1;
        do {
            realIndex++;
            if (!workbook.isSheetVeryHidden(realIndex)
                    && !workbook.isSheetHidden(realIndex)) {
                i++;
            }
        } while (i < visibleSheetIndex
                && realIndex < (workbook.getNumberOfSheets() - 1));
        return realIndex;
    }

    /**
     * Gets the Spreadsheet sheet-index for the sheet at the given POI index.
     * Index will be returned for a visible sheet only.
     * 
     * @param poiSheetIndex
     *            Index of the target sheet within the POI model, 0-based
     * @return Index of the target sheet in the Spreadsheet, 0-based
     */
    private int getSpreadsheetSheetIndex(int poiSheetIndex) {
        int ourIndex = -1;
        for (int i = 0; i <= poiSheetIndex; i++) {
            if (!workbook.isSheetVeryHidden(i) && !workbook.isSheetHidden(i)) {
                ourIndex++;
            }
        }
        return ourIndex;
    }

    /**
     * Gets the protection state of the sheet at the given POI index.
     * 
     * @param poiSheetIndex
     *            Index of the target sheet within the POI model, 0-based
     * @return true if the target {@link Sheet} is protected, false otherwise.
     */
    public boolean isSheetProtected(int poiSheetIndex) {
        return workbook.getSheetAt(poiSheetIndex).getProtect();
    }

    /**
     * Gets the protection state of the current sheet.
     * 
     * @return true if the current {@link Sheet} is protected, false otherwise.
     */
    public boolean isActiveSheetProtected() {
        return getState().sheetProtected;
    }

    /**
     * Gets the visibility state of the given cell.
     * 
     * @param cell
     *            The cell to check
     * @return true if the cell is hidden, false otherwise
     */
    public boolean isCellHidden(Cell cell) {
        return isActiveSheetProtected() && cell.getCellStyle().getHidden();
    }

    /**
     * Gets the locked state of the given cell.
     * 
     * @param cell
     *            The cell to check
     * @return true if the cell is locked, false otherwise
     */
    public boolean isCellLocked(Cell cell) {
        return isActiveSheetProtected()
                && (cell == null || cell.getCellStyle().getLocked());
    }

    /**
     * Gets the RPC proxy for communication to the client side.
     * 
     * @return Client RPC proxy instance
     */
    protected SpreadsheetClientRpc getRpcProxy() {
        return getRpcProxy(SpreadsheetClientRpc.class);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#beforeClientResponse(boolean)
     */
    @Override
    public void beforeClientResponse(boolean initial) {
        super.beforeClientResponse(initial);
        if (reload) {
            reload = false;
            getState().reload = true;
            if (initialSheetSelection == null) {
                initialSheetSelection = "A1";
            }
        } else {
            getState().reload = false;
        }
    }

    /**
     * Gets the currently used style factory for this Spreadsheet.
     * 
     * @return The current style factory.
     */
    public SpreadsheetStyleFactory getSpreadsheetStyleFactory() {
        return styler;
    }

    /**
     * Note that modifications done directly with the POI {@link Workbook} API
     * will not get automatically updated into the Spreadsheet component.
     * <p>
     * Use {@link #markCellAsDeleted(Cell, boolean)},
     * {@link #markCellAsUpdated(Cell, boolean)}, or
     * {@link #reloadVisibleCellContents()} to update content.
     * 
     * @return The currently presented workbook
     */
    public Workbook getWorkbook() {
        return workbook;
    }

    /**
     * Reloads the component with the given Workbook.
     * 
     * @param workbook
     *            New workbook to load
     */
    public void setWorkbook(Workbook workbook) {
        if (workbook == null) {
            throw new NullPointerException(
                    "Cannot open a null workbook with Spreadsheet component.");
        }
        SpreadsheetFactory.reloadSpreadsheetComponent(this, workbook);
    }

    /**
     * Note that modifications done directly with the POI {@link Sheet} API will
     * not get automatically updated into the Spreadsheet component.
     * <p>
     * Use {@link #markCellAsDeleted(Cell, boolean)},
     * {@link #markCellAsUpdated(Cell, boolean)}, or
     * {@link #reloadVisibleCellContents()} to update content.
     * 
     * @return The currently active (= visible) sheet
     */
    public Sheet getActiveSheet() {
        return workbook.getSheetAt(workbook.getActiveSheetIndex());
    }

    /**
     * Updates the given range of cells. Takes frozen panes in to account.
     * 
     * NOTE: Does not run style updates!
     */
    private void updateRowAndColumnRangeCellData(int r1, int c1, int r2, int c2) {
        // FIXME should be optimized, should not go through all links, comments
        // etc. always
        loadHyperLinks();
        loadCellComments();
        loadImages();
        loadPopupButtons();
        // custom components not updated here on purpose

        valueManager.loadCellData(r1, c1, r2, c2);
    }

    /**
     * Sends data of the given cell area to client side. Data is only sent once,
     * unless there are changes. Cells with custom components are skipped.
     * 
     * @param firstRow
     *            Index of the starting row, 1-based
     * @param firstColumn
     *            Index of the starting column, 1-based
     * @param lastRow
     *            Index of the ending row, 1-based
     * @param lastColumn
     *            Index of the ending column, 1-based
     */
    private void loadCells(int firstRow, int firstColumn, int lastRow,
            int lastColumn) {
        loadCustomComponents();
        loadHyperLinks();
        loadCellComments();
        loadImages();
        loadTables();
        loadPopupButtons();
        valueManager.loadCellData(firstRow, firstColumn, lastRow, lastColumn);
    }

    void onLinkCellClick(int row, int column) {
        Cell cell = getActiveSheet().getRow(row - 1).getCell(column - 1);
        if (hyperlinkCellClickHandler != null) {
            hyperlinkCellClickHandler.onHyperLinkCellClick(cell,
                    cell.getHyperlink(), Spreadsheet.this);
        } else {
            DefaultHyperlinkCellClickHandler.get().onHyperLinkCellClick(cell,
                    cell.getHyperlink(), Spreadsheet.this);
        }
    }

    void onRowResized(Map<Integer, Float> newRowSizes, int row1, int col1,
            int row2, int col2) {
        SizeChangeCommand command = new SizeChangeCommand(this, Type.ROW);
        command.captureValues(newRowSizes.keySet().toArray(
                new Integer[newRowSizes.size()]));
        historyManager.addCommand(command);
        for (Entry<Integer, Float> entry : newRowSizes.entrySet()) {
            int index = entry.getKey();
            float height = entry.getValue();
            setRowHeight(index - 1, height);
        }
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
        }
        loadCells(row1, col1, row2, col2);
    }

    /**
     * Sets the row height for currently active sheet. Updates both POI model
     * and the visible sheet.
     * 
     * @param index
     *            Index of target row, 0-based
     * @param height
     *            New row height in points
     */
    public void setRowHeight(int index, float height) {
        if (height == 0.0F) {
            setRowHidden(index, true);
        } else {
            Row row = getActiveSheet().getRow(index);
            if (getState().hiddenRowIndexes
                    .contains(Integer.valueOf(index + 1))) {
                getState().hiddenRowIndexes.remove(Integer.valueOf(index + 1));
                if (row != null && row.getZeroHeight()) {
                    row.setZeroHeight(false);
                }
            }
            getState().rowH[index] = height;
            if (row == null) {
                row = getActiveSheet().createRow(index);
            }
            row.setHeightInPoints(height);
        }
    }

    void onColumnResized(Map<Integer, Integer> newColumnSizes, int row1,
            int col1, int row2, int col2) {
        SizeChangeCommand command = new SizeChangeCommand(this, Type.COLUMN);
        command.captureValues(newColumnSizes.keySet().toArray(
                new Integer[newColumnSizes.size()]));
        historyManager.addCommand(command);
        for (Entry<Integer, Integer> entry : newColumnSizes.entrySet()) {
            int index = entry.getKey();
            int width = entry.getValue();
            setColumnWidth(index - 1, width);
        }
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
        }
        loadCells(row1, col1, row2, col2);
    }

    /**
     * Sets the column width in pixels (using conversion) for the currently
     * active sheet. Updates both POI model and the visible sheet.
     * 
     * @param index
     *            Index of target column, 0-based
     * @param width
     *            New column width in pixels
     */
    public void setColumnWidth(int index, int width) {
        if (width == 0) {
            setColumnHidden(index, true);
        } else {
            if (getState().hiddenColumnIndexes.contains(Integer
                    .valueOf(index + 1))) {
                getState().hiddenColumnIndexes.remove(Integer
                        .valueOf(index + 1));
            }
            if (getActiveSheet().isColumnHidden(index)) {
                getActiveSheet().setColumnHidden(index, false);
            }
            getState().colW[index] = width;
            getActiveSheet().setColumnWidth(index,
                    SpreadsheetUtil.pixel2WidthUnits(width));
            getCellValueManager().clearCacheForColumn(index + 1);
            getCellValueManager().loadCellData(firstRow, index + 1, lastRow,
                    index + 1);
        }
    }

    private void loadHyperLinks() {
        if (getState(false).hyperlinksTooltips == null) {
            getState(false).hyperlinksTooltips = new HashMap<String, String>();
        } else {
            getState().hyperlinksTooltips.clear();
        }
        if (getVerticalSplitPosition() > 0 && getHorizontalSplitPosition() > 0
                && !topLeftCellHyperlinksLoaded) {
            loadHyperLinks(1, 1, getVerticalSplitPosition(),
                    getHorizontalSplitPosition());
        }
        if (getVerticalSplitPosition() > 0) {
            loadHyperLinks(1, firstColumn, getVerticalSplitPosition(),
                    lastColumn);
        }
        if (getHorizontalSplitPosition() > 0) {
            loadHyperLinks(firstRow, 1, lastRow, getHorizontalSplitPosition());
        }
        loadHyperLinks(firstRow, firstColumn, lastRow, lastColumn);
    }

    private void loadHyperLinks(int r1, int c1, int r2, int c2) {
        for (int r = r1 - 1; r < r2; r++) {
            final Row row = getActiveSheet().getRow(r);
            if (row != null) {
                for (int c = c1 - 1; c < c2; c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null) {
                        try {
                            Hyperlink link = cell.getHyperlink();
                            if (link != null) {
                                if (link instanceof XSSFHyperlink) {
                                    String tooltip = ((XSSFHyperlink) link)
                                            .getTooltip();
                                    // Show address if no defined tooltip (like
                                    // in
                                    // excel)
                                    if (tooltip == null) {
                                        tooltip = link.getAddress();
                                    }
                                    getState().hyperlinksTooltips
                                            .put(SpreadsheetUtil.toKey(c + 1,
                                                    r + 1), tooltip);
                                } else {
                                    getState().hyperlinksTooltips
                                            .put(SpreadsheetUtil.toKey(c + 1,
                                                    r + 1), link.getAddress());
                                }
                            } else {
                                // Check if the cell has HYPERLINK function
                                if (DefaultHyperlinkCellClickHandler
                                        .isHyperlinkFormulaCell(cell)) {
                                    getState().hyperlinksTooltips
                                            .put(SpreadsheetUtil.toKey(c + 1,
                                                    r + 1),
                                                    DefaultHyperlinkCellClickHandler
                                                            .getHyperlinkFunctionCellAddress(cell));
                                }
                            }
                        } catch (XmlValueDisconnectedException exc) {
                            LOGGER.log(Level.FINEST, exc.getMessage(), exc);
                        }
                    }
                }
            }
        }
    }

    /**
     * Triggers image reload from POI model (only if there are images present).
     */
    void triggerImageReload() {
        if (sheetImages != null) {
            reloadImageSizesFromPOI = true;
        }
    }

    private void loadImages() {
        if (sheetImages.isEmpty()) {
            getState().resourceKeyToImage = null;
        } else {
            if (getState(false).resourceKeyToImage == null) {
                getState(false).resourceKeyToImage = new HashMap<String, ImageInfo>();
            }
            // reload images from POI because row / column sizes have changed
            // currently doesn't effect anything because POI doesn't update the
            // image anchor data after resizing
            if (reloadImageSizesFromPOI) {
                for (SheetImageWrapper image : sheetImages) {
                    if (image.isVisible()) {
                        getState().resourceKeyToImage.remove(image
                                .getResourceKey());
                        setResource(image.getResourceKey(), null);
                    }
                }
                sheetImages.clear();
                SpreadsheetFactory.loadSheetImages(this);
            }
            for (final SheetImageWrapper image : sheetImages) {
                if (isImageVisible(image)) {
                    if (!getState(false).resourceKeyToImage.containsKey(image
                            .getResourceKey())) {
                        ImageInfo imageInfo = new ImageInfo();
                        generateImageInfo(image, imageInfo);
                        getState().resourceKeyToImage.put(
                                image.getResourceKey(), imageInfo);
                        if (image.getResource() == null) {
                            StreamSource streamSource = new StreamSource() {

                                @Override
                                public InputStream getStream() {
                                    return new ByteArrayInputStream(
                                            image.getData());
                                }
                            };
                            StreamResource resource = new StreamResource(
                                    streamSource, image.getResourceKey());
                            resource.setMIMEType(image.getMIMEType());
                            setResource(image.getResourceKey(), resource);
                            image.setResource(resource);
                        }
                        image.setVisible(true);
                    } else {
                        generateImageInfo(image,
                                getState(false).resourceKeyToImage.get(image
                                        .getResourceKey()));
                    }
                } else if (image.isVisible()) {
                    getState().resourceKeyToImage
                            .remove(image.getResourceKey());
                    image.setVisible(false);
                }
            }
        }
        reloadImageSizesFromPOI = false;
    }

    private boolean isImageVisible(SheetImageWrapper image) {
        int horizontalSplitPosition = getHorizontalSplitPosition();
        int verticalSplitPosition = getVerticalSplitPosition();
        return (horizontalSplitPosition > 0 && verticalSplitPosition > 0 && image
                .isVisible(1, 1, verticalSplitPosition, horizontalSplitPosition))
                || (horizontalSplitPosition > 0 && image.isVisible(firstRow, 1,
                        lastRow, horizontalSplitPosition))
                || (verticalSplitPosition > 0 && image.isVisible(1,
                        firstColumn, verticalSplitPosition, lastColumn))
                || image.isVisible(firstRow, firstColumn, lastRow, lastColumn);

    }

    private void generateImageInfo(final SheetImageWrapper image,
            final ImageInfo info) {
        Sheet sheet = getActiveSheet();

        int col = image.getAnchor().getCol1();
        while (sheet.isColumnHidden(col) && col < (getState(false).cols - 1)) {
            col++;
        }
        int row = image.getAnchor().getRow1();
        Row r = sheet.getRow(row);
        while (r != null && r.getZeroHeight()) {
            row++;
            r = sheet.getRow(row);
        }

        info.col = col + 1; // 1-based
        info.row = row + 1; // 1-based
        info.height = image.getHeight(sheet, getState(false).rowH);
        info.width = image.getWidth(sheet, getState(false).colW,
                getState(false).defColW);
        info.dx = image.getDx1(sheet);
        info.dy = image.getDy1(sheet);
    }

    private void loadCellComments() {
        if (getState(false).cellComments == null) {
            getState(false).cellComments = new HashMap<String, String>();
        } else {
            getState().cellComments.clear();
        }
        if (getState(false).cellCommentAuthors == null) {
            getState(false).cellCommentAuthors = new HashMap<String, String>();
        } else {
            getState().cellCommentAuthors.clear();
        }
        if (getState(false).visibleCellComments == null) {
            getState(false).visibleCellComments = new ArrayList<String>();
        } else {
            getState().visibleCellComments.clear();
        }
        if (getVerticalSplitPosition() > 0 && getHorizontalSplitPosition() > 0
                && !topLeftCellCommentsLoaded) {
            loadCellComments(1, 1, getVerticalSplitPosition(),
                    getHorizontalSplitPosition());
        }
        if (getVerticalSplitPosition() > 0) {
            loadCellComments(1, firstColumn, getVerticalSplitPosition(),
                    lastColumn);
        }
        if (getHorizontalSplitPosition() > 0) {
            loadCellComments(firstRow, 1, lastRow, getHorizontalSplitPosition());
        }
        loadCellComments(firstRow, firstColumn, lastRow, lastColumn);
    }

    private void loadCellComments(int r1, int c1, int r2, int c2) {
        Sheet sheet = getActiveSheet();
        for (int r = r1 - 1; r < r2; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getZeroHeight()) {
                continue;
            }
            for (int c = c1 - 1; c < c2; c++) {
                if (sheet.isColumnHidden(c)) {
                    continue;
                }
                MergedRegion region = mergedRegionContainer.getMergedRegion(
                        c + 1, r + 1);
                // do not add comments that are "below" merged regions.
                // client side handles cases where comment "moves" (because
                // shifting etc.) from merged cell into basic or vice versa.
                if (region == null || region.col1 == (c + 1)
                        && region.row1 == (r + 1)) {
                    Comment comment = sheet.getCellComment(r, c);
                    if (comment != null) {
                        // by default comments are shown when mouse is over the
                        // red
                        // triangle on the cell's top right corner. the comment
                        // position is calculated so that it is completely
                        // visible.
                        String key = SpreadsheetUtil.toKey(c + 1, r + 1);
                        getState().cellComments.put(key, comment.getString()
                                .getString());
                        getState().cellCommentAuthors.put(key,
                                comment.getAuthor());
                        if (comment.isVisible()) {
                            getState().visibleCellComments.add(key);
                        }
                    }
                } else {
                    c = region.col2 - 1;
                }
            }
        }
    }

    /**
     * Loads the custom components for the currently viewed cells and clears
     * previous components that are not currently visible.
     */
    private void loadCustomComponents() {
        if (customComponentFactory != null) {
            if (getState().cellKeysToEditorIdMap == null) {
                getState().cellKeysToEditorIdMap = new HashMap<String, String>();
            } else {
                getState().cellKeysToEditorIdMap.clear();
            }
            if (getState().componentIDtoCellKeysMap == null) {
                getState().componentIDtoCellKeysMap = new HashMap<String, String>();
            } else {
                getState().componentIDtoCellKeysMap.clear();
            }
            if (customComponents == null) {
                customComponents = new HashSet<Component>();
            }
            HashSet<Component> newCustomComponents = new HashSet<Component>();
            // iteration indexes 0-based
            for (int r = firstRow - 1; r < lastRow; r++) {
                final Row row = getActiveSheet().getRow(r);
                for (int c = firstColumn - 1; c < lastColumn; c++) {
                    // Cells that are inside a merged region are skipped:
                    MergedRegion region = mergedRegionContainer
                            .getMergedRegion(c + 1, r + 1);
                    if (region == null
                            || (region.col1 == (c + 1) && region.row1 == (r + 1))) {
                        Cell cell = null;
                        if (row != null) {
                            cell = row.getCell(c);
                        }
                        // check if the cell has a custom component
                        Component customComponent = customComponentFactory
                                .getCustomComponentForCell(cell, r, c, this,
                                        getActiveSheet());
                        if (customComponent != null) {
                            final String key = SpreadsheetUtil.toKey(c + 1,
                                    r + 1);
                            if (!customComponents.contains(customComponent)) {
                                registerCustomComponent(customComponent);
                            }
                            getState().componentIDtoCellKeysMap.put(
                                    customComponent.getConnectorId(), key);
                            newCustomComponents.add(customComponent);
                        } else if (!isCellLocked(cell)) {
                            // no custom component and not locked, check if
                            // the cell has a custom editor
                            Component customEditor = customComponentFactory
                                    .getCustomEditorForCell(cell, r, c, this,
                                            getActiveSheet());
                            if (customEditor != null) {
                                final String key = SpreadsheetUtil.toKey(c + 1,
                                        r + 1);
                                if (!newCustomComponents.contains(customEditor)
                                        && !customComponents
                                                .contains(customEditor)) {
                                    registerCustomComponent(customEditor);
                                }
                                getState().cellKeysToEditorIdMap.put(key,
                                        customEditor.getConnectorId());
                                newCustomComponents.add(customEditor);
                            }
                        }
                    }
                    if (region != null) {
                        c = region.col2;
                    }
                }
            }
            // unregister old
            for (Iterator<Component> i = customComponents.iterator(); i
                    .hasNext();) {
                Component c = i.next();
                if (!newCustomComponents.contains(c)) {
                    unRegisterCustomComponent(c);
                    i.remove();
                }
            }
            customComponents = newCustomComponents;
        } else {
            getState().cellKeysToEditorIdMap = null;
            getState().componentIDtoCellKeysMap = null;
            if (customComponents != null && !customComponents.isEmpty()) {
                for (Component c : customComponents) {
                    unRegisterCustomComponent(c);
                }
                customComponents.clear();
            }
        }
    }

    /**
     * Determines if the cell at the given coordinates is currently visible
     * (rendered) in the browser.
     * 
     * @param row
     *            Row index, 1-based
     * @param col
     *            Column index, 1-based
     * 
     * @return True if the cell is visible, false otherwise
     */
    private boolean isCellVisible(int row, int col) {
        int verticalSplitPosition = getVerticalSplitPosition();
        int horizontalSplitPosition = getHorizontalSplitPosition();
        return (col >= firstColumn && col <= lastColumn && row >= firstRow && row <= lastRow)
                || (col >= 1 && col <= horizontalSplitPosition && row >= 1 && row <= verticalSplitPosition)
                || (col >= firstColumn && col <= lastColumn && row >= 1 && row <= verticalSplitPosition)
                || (col >= 1 && col <= horizontalSplitPosition
                        && row >= firstRow && row <= lastRow);
    }

    private void registerCustomComponent(Component component) {
        if (!equals(component.getParent())) {
            component.setParent(this);
        }
    }

    private void unRegisterCustomComponent(Component component) {
        component.setParent(null);
    }

    /**
     * Set a new component factory for this Spreadsheet. If a {@link Workbook}
     * has been set, all components will be reloaded.
     * 
     * @param customComponentFactory
     *            The new component factory to use.
     */
    public void setSpreadsheetComponentFactory(
            SpreadsheetComponentFactory customComponentFactory) {
        this.customComponentFactory = customComponentFactory;
        if (firstRow != -1) {
            loadCustomComponents();
            loadCustomEditorOnSelectedCell();
        } else {
            getState().cellKeysToEditorIdMap = null;
            if (customComponents != null && !customComponents.isEmpty()) {
                for (Component c : customComponents) {
                    unRegisterCustomComponent(c);
                }
                customComponents.clear();
            }
        }
    }

    /**
     * Gets the current SpreadsheetComponentFactory.
     * 
     * @return The currently used component factory.
     */
    public SpreadsheetComponentFactory getSpreadsheetComponentFactory() {
        return customComponentFactory;
    }

    /**
     * Sets a pop-up button to the given cell in the currently active sheet. If
     * there is already a pop-up button in the given cell, it will be replaced.
     * <p>
     * Note that if the active sheet is changed, all pop-up buttons are removed
     * from the spreadsheet.
     * 
     * @param cellAddress
     *            address to the target cell, e.g. "C3"
     * @param popupButton
     *            PopupButton to set for the target cell. Passing null here
     *            removes the pop-up button for the target cell.
     */
    public void setPopup(String cellAddress, PopupButton popupButton) {
        setPopup(new CellReference(cellAddress), popupButton);
    }

    /**
     * Sets a pop-up button to the given cell in the currently active sheet. If
     * there is already a pop-up button in the given cell, it will be replaced.
     * <p>
     * Note that if the active sheet is changed, all pop-up buttons are removed
     * from the spreadsheet.
     * 
     * @param row
     *            Row index of target cell, 0-based
     * @param col
     *            Column index of target cell, 0-based
     * @param popupButton
     *            PopupButton to set for the target cell. Passing null here
     *            removes the pop-up button for the target cell.
     */
    public void setPopup(int row, int col, PopupButton popupButton) {
        setPopup(new CellReference(row, col), popupButton);
    }

    /**
     * Sets a pop-up button to the given cell in the currently active sheet. If
     * there is already a pop-up button in the given cell, it will be replaced.
     * <p>
     * Note that if the active sheet is changed, all pop-up buttons are removed
     * from the spreadsheet.
     * 
     * @param cellReference
     *            Reference to the target cell
     * @param popupButton
     *            PopupButton to set for the target cell. Passing null here
     *            removes the pop-up button for the target cell.
     */
    public void setPopup(CellReference cellReference, PopupButton popupButton) {
        removePopupButton(cellReference);
        if (popupButton != null) {
            popupButton.setCellReference(cellReference);
            sheetPopupButtons.put(cellReference, popupButton);
            if (isCellVisible(cellReference.getRow() + 1,
                    cellReference.getCol() + 1)) {
                registerCustomComponent(popupButton);
                markAsDirty();
            }
        }
    }

    private void removePopupButton(CellReference cellReference) {
        PopupButton oldButton = sheetPopupButtons.get(cellReference);
        if (oldButton != null) {
            unRegisterCustomComponent(oldButton);
            sheetPopupButtons.remove(cellReference);
            markAsDirty();
        }
    }

    /**
     * Registers and unregister pop-up button components for the currently
     * visible cells.
     */
    private void loadPopupButtons() {
        if (sheetPopupButtons != null) {
            for (PopupButton popupButton : sheetPopupButtons.values()) {
                int column = popupButton.getColumn() + 1;
                int row = popupButton.getRow() + 1;
                if (isCellVisible(row, column)) {
                    registerCustomComponent(popupButton);
                } else {
                    unRegisterCustomComponent(popupButton);
                }
            }
        }
    }

    /**
     * Registers the given table to this Spreadsheet, meaning that this table
     * will be reloaded when the active sheet changes to the sheet containing
     * the table.
     * <p>
     * Populating the table content (pop-up button and other content) is the
     * responsibility of the table, with {@link SpreadsheetTable#reload()}.
     * <p>
     * When the sheet is changed to a different sheet than the one that the
     * table belongs to, the table contents are cleared with
     * {@link SpreadsheetTable#clear()}. If the table is a filtering table, the
     * filters are NOT cleared (can be done with
     * {@link SpreadsheetFilterTable#clearAllFilters()}.
     * <p>
     * The pop-up buttons are always removed by the spreadsheet when the sheet
     * changes.
     * 
     * @param table
     *            The table to register
     */
    public void registerTable(SpreadsheetTable table) {
        tables.add(table);
    }

    /**
     * Unregisters the given table from this Spreadsheet - it will no longer get
     * reloaded when the sheet is changed back to the sheet containing the
     * table. This does not delete any table content, use
     * {@link #deleteTable(SpreadsheetTable)} to completely remove the table.
     * <p>
     * See {@link #registerTable(SpreadsheetTable)}.
     * 
     * @param table
     *            The table to unregister
     */
    public void unregisterTable(SpreadsheetTable table) {
        tables.remove(table);
    }

    /**
     * Deletes the given table: removes it from "memory" (see
     * {@link #registerTable(SpreadsheetTable)}), clears and removes all
     * possible filters (if table is a {@link SpreadsheetFilterTable}), and
     * clears all table pop-up buttons and content.
     * 
     * @param table
     *            The table to delete
     */
    public void deleteTable(SpreadsheetTable table) {
        unregisterTable(table);
        if (table.isTableSheetCurrentlyActive()) {
            for (PopupButton popupButton : table.getPopupButtons()) {
                removePopupButton(popupButton.getCellReference());
            }
            if (table instanceof SpreadsheetFilterTable) {
                ((SpreadsheetFilterTable) table).clearAllFilters();
            }
            table.clear();
        }
    }

    /**
     * Gets all the tables that have been registered to this Spreadsheet. See
     * {@link #registerTable(SpreadsheetTable)}.
     * 
     * @return All tables for this spreadsheet
     */
    public HashSet<SpreadsheetTable> getTables() {
        return tables;
    }

    /**
     * Gets the tables that belong to the currently active sheet (
     * {@link #getActiveSheet()}). See {@link #registerTable(SpreadsheetTable)}.
     * 
     * @return All tables for the currently active sheet
     */
    public List<SpreadsheetTable> getTablesForActiveSheet() {
        List<SpreadsheetTable> temp = new ArrayList<SpreadsheetTable>();
        for (SpreadsheetTable table : tables) {
            if (table.getSheet().equals(getActiveSheet())) {
                temp.add(table);
            }
        }
        return temp;
    }

    /**
     * Reload tables for current sheet
     */
    private void loadTables() {
        if (!tablesLoaded) {
            for (SpreadsheetTable table : tables) {
                if (table.getSheet().equals(getActiveSheet())) {
                    table.reload();
                }
            }
            tablesLoaded = true;
        }
    }

    /**
     * Returns the formatted value for the given cell, using the
     * {@link DataFormatter} with the current locale.
     * 
     * See {@link DataFormatter#formatCellValue(Cell, FormulaEvaluator)}.
     * 
     * @param cell
     *            Cell to get the value from
     * @return Formatted value
     */
    public final String getCellValue(Cell cell) {
        return valueManager.getDataFormatter().formatCellValue(cell,
                valueManager.getFormulaEvaluator());
    }

    /**
     * Gets grid line visibility for the currently active sheet.
     * 
     * @return True if grid lines are visible, false if they are hidden
     */
    public boolean isGridlinesVisible() {
        if (getActiveSheet() != null) {
            return getActiveSheet().isDisplayGridlines();
        }
        return true;
    }

    /**
     * Sets grid line visibility for the currently active sheet.
     * 
     * @param visible
     *            True to show grid lines, false to hide them
     */
    public void setGridlinesVisible(boolean visible) {
        if (getActiveSheet() == null) {
            throw new NullPointerException("no active sheet");
        }
        getActiveSheet().setDisplayGridlines(visible);
        getState().displayGridlines = visible;
    }

    /**
     * Gets row and column heading visibility for the currently active sheet.
     * 
     * @return true if headings are visible, false if they are hidden
     */
    public boolean isRowColHeadingsVisible() {
        if (getActiveSheet() != null) {
            return getActiveSheet().isDisplayRowColHeadings();
        }
        return true;
    }

    /**
     * Sets row and column heading visibility for the currently active sheet.
     * 
     * @param visible
     *            true to show headings, false to hide them
     */
    public void setRowColHeadingsVisible(boolean visible) {
        if (getActiveSheet() == null) {
            throw new NullPointerException("no active sheet");
        }
        getActiveSheet().setDisplayRowColHeadings(visible);
        getState().displayRowColHeadings = visible;
    }

    /**
     * This event is fired when cell value changes.
     */
    public static class CellValueChangeEvent extends Component.Event {

        private final CellRangeAddress changedCells;

        public CellValueChangeEvent(Component source,
                CellRangeAddress cellRangeAddress) {
            super(source);
            changedCells = cellRangeAddress;
        }

        public CellRangeAddress getChangedCells() {
            return changedCells;
        }
    }

    /**
     * This event is fired when cell selection changes.
     */
    public static class SelectionChangeEvent extends Component.Event {

        private final CellReference selectedCellReference;
        private final List<CellReference> individualSelectedCells;
        private final CellRangeAddress selectedCellMergedRegion;
        private final List<CellRangeAddress> cellRangeAddresses;

        /**
         * Creates a new selection change event.
         * 
         * @param source
         *            Source Spreadsheet
         * @param selectedCellReference
         *            see {@link #getSelectedCellReference()}
         * @param individualSelectedCells
         *            see {@link #getIndividualSelectedCells()}
         * @param selectedCellMergedRegion
         *            see {@link #getSelectedCellMergedRegion()}
         * @param cellRangeAddresses
         *            see {@link #getCellRangeAddresses()}
         */
        public SelectionChangeEvent(Component source,
                CellReference selectedCellReference,
                List<CellReference> individualSelectedCells,
                CellRangeAddress selectedCellMergedRegion,
                List<CellRangeAddress> cellRangeAddresses) {
            super(source);
            this.selectedCellReference = selectedCellReference;
            this.individualSelectedCells = individualSelectedCells;
            this.selectedCellMergedRegion = selectedCellMergedRegion;
            this.cellRangeAddresses = cellRangeAddresses;
        }

        /**
         * Gets the Spreadsheet where this event happened.
         * 
         * @return Source Spreadsheet
         */
        public Spreadsheet getSpreadsheet() {
            return (Spreadsheet) getSource();
        }

        /**
         * Returns reference to the currently selected single cell OR in case of
         * multiple selections the last cell clicked OR in case of area select
         * the cell from which the area selection was started.
         * 
         * @return CellReference to the single selected cell, or the last cell
         *         selected manually (e.g. with ctrl+mouseclick)
         */
        public CellReference getSelectedCellReference() {
            return selectedCellReference;
        }

        /**
         * Gets all the individually selected single cells in the current
         * selection.
         * 
         * @return All non-contiguously selected cells (e.g. with
         *         ctrl+mouseclick)
         */
        public List<CellReference> getIndividualSelectedCells() {
            return individualSelectedCells;
        }

        /**
         * Gets the merged region the single selected cell is a part of, if
         * applicable.
         * 
         * @return The {@link CellRangeAddress} described the merged region the
         *         single selected cell is part of, if any.
         */
        public CellRangeAddress getSelectedCellMergedRegion() {
            return selectedCellMergedRegion;
        }

        /**
         * Gets all separately selected cell ranges.
         * 
         * @return All separately selected cell ranges (e.g. with
         *         ctrl+shift+mouseclick)
         */
        public List<CellRangeAddress> getCellRangeAddresses() {
            return cellRangeAddresses;
        }

        /**
         * Gets a combination of all selected cells.
         * 
         * @return A combination of all selected cells, regardless of selection
         *         mode. Doesn't contain duplicates.
         */
        public Set<CellReference> getAllSelectedCells() {
            return Spreadsheet.getAllSelectedCells(selectedCellReference,
                    individualSelectedCells, cellRangeAddresses);

        }
    }

    private static Set<CellReference> getAllSelectedCells(
            CellReference selectedCellReference,
            List<CellReference> individualSelectedCells,
            List<CellRangeAddress> cellRangeAddresses) {
        Set<CellReference> cells = new HashSet<CellReference>();
        for (CellReference r : individualSelectedCells) {
            cells.add(r);
        }
        cells.add(selectedCellReference);

        if (cellRangeAddresses != null) {
            for (CellRangeAddress a : cellRangeAddresses) {

                for (int x = a.getFirstColumn(); x <= a.getLastColumn(); x++) {
                    for (int y = a.getFirstRow(); y <= a.getLastRow(); y++) {
                        cells.add(new CellReference(y, x));
                    }
                }
            }
        }
        return cells;
    }

    /**
     * Used for knowing when a user has changed the cell selection in any way.
     */
    public interface SelectionChangeListener extends Serializable {
        public static final Method SELECTION_CHANGE_METHOD = ReflectTools
                .findMethod(SelectionChangeListener.class, "onSelectionChange",
                        SelectionChangeEvent.class);

        /**
         * This is called when user changes cell selection.
         * 
         * @param event
         *            SelectionChangeEvent that happened
         */
        public void onSelectionChange(SelectionChangeEvent event);
    }

    /**
     * Used for knowing when a user has changed the cell value in Spreadsheet
     * UI.
     */
    public interface CellValueChangeListener extends Serializable {
        public static final Method CELL_VALUE_CHANGE_METHOD = ReflectTools
                .findMethod(CellValueChangeListener.class, "onCellValueChange",
                        CellValueChangeEvent.class);

        /**
         * This is called when user changes the cell value in Spreadsheet.
         * 
         * @param event
         *            CellValueChangeEvent that happened
         */
        public void onCellValueChange(CellValueChangeEvent event);
    }

    /**
     * Adds the given SelectionChangeListener to this Spreadsheet.
     * 
     * @param listener
     *            Listener to add.
     */
    public void addSelectionChangeListener(SelectionChangeListener listener) {
        addListener(SelectionChangeEvent.class, listener,
                SelectionChangeListener.SELECTION_CHANGE_METHOD);
    }

    /**
     * Adds the given CellValueChangeListener to this Spreadsheet.
     * 
     * @param listener
     *            Listener to add.
     */
    public void addCellValueChangeListener(CellValueChangeListener listener) {
        addListener(CellValueChangeEvent.class, listener,
                CellValueChangeListener.CELL_VALUE_CHANGE_METHOD);
    }

    /**
     * Removes the given SelectionChangeListener from this Spreadsheet.
     * 
     * @param listener
     *            Listener to remove.
     */
    public void removeSelectionChangeListener(SelectionChangeListener listener) {
        removeListener(SelectionChangeEvent.class, listener,
                SelectionChangeListener.SELECTION_CHANGE_METHOD);
    }

    /**
     * Removes the given CellValueChangeListener from this Spreadsheet.
     * 
     * @param listener
     *            Listener to remove.
     */
    public void removeCellValueChangeListener(CellValueChangeListener listener) {
        removeListener(CellValueChangeEvent.class, listener,
                CellValueChangeListener.CELL_VALUE_CHANGE_METHOD);
    }

    /**
     * An event that is fired when an attempt to modify a locked cell has been
     * made.
     */
    public static class ProtectedEditEvent extends Component.Event {

        public ProtectedEditEvent(Component source) {
            super(source);
        }
    }

    /**
     * A listener for when an attempt to modify a locked cell has been made.
     */
    public interface ProtectedEditListener extends Serializable {
        public static final Method SELECTION_CHANGE_METHOD = ReflectTools
                .findMethod(ProtectedEditListener.class, "writeAttempted",
                        ProtectedEditEvent.class);

        /**
         * Called when the SpreadSheet detects that the client tried to edit a
         * locked cell (usually by pressing a key). Method is not called for
         * each such event; instead, the SpreadSheet waits a second before
         * sending a new event. This is done to give the user time to react to
         * the results of this call (e.g. showing a notification).
         * 
         * @param event
         *            ProtectedEditEvent that happened
         */
        public void writeAttempted(ProtectedEditEvent event);
    }

    /**
     * Add listener for when an attempt to modify a locked cell has been made.
     * 
     * @param listener
     *            The listener to add.
     */
    public void addProtectedEditListener(ProtectedEditListener listener) {
        addListener(ProtectedEditEvent.class, listener,
                ProtectedEditListener.SELECTION_CHANGE_METHOD);
    }

    /**
     * Removes the given ProtectedEditListener.
     * 
     * @param listener
     *            The listener to remove.
     */
    public void removeProtectedEditListener(ProtectedEditListener listener) {
        removeListener(ProtectedEditEvent.class, listener,
                ProtectedEditListener.SELECTION_CHANGE_METHOD);
    }

    /**
     * Creates or removes a freeze pane from the currently active sheet.
     * 
     * If both colSplit and rowSplit are zero then the existing freeze pane is
     * removed.
     * 
     * @param rowSplit
     *            Vertical position of the split, 1-based row index
     * @param colSplit
     *            Horizontal position of the split, 1-based column index
     */
    public void createFreezePane(int rowSplit, int colSplit) {
        getActiveSheet().createFreezePane(colSplit, rowSplit);
        SpreadsheetFactory.loadFreezePane(this);
        reloadActiveSheetData();
    }

    /**
     * Removes the freeze pane from the currently active sheet if one is
     * present.
     */
    public void removeFreezePane() {
        PaneInformation paneInformation = getActiveSheet().getPaneInformation();
        if (paneInformation != null && paneInformation.isFreezePane()) {
            getActiveSheet().createFreezePane(0, 0);
            SpreadsheetFactory.loadFreezePane(this);
            reloadActiveSheetData();
        }
    }

    /**
     * Gets a reference to the current single selected cell.
     * 
     * @return Reference to the currently selected single cell.
     *         <p>
     *         <em>NOTE:</em> other cells might also be selected: use
     *         {@link #addSelectionChangeListener(SelectionChangeListener)} to
     *         get notified for all selection changes or call
     *         {@link #getSelectedCellReferences()}.
     */
    public CellReference getSelectedCellReference() {
        return selectionManager.getSelectedCellReference();
    }

    /**
     * Gets all the currently selected cells.
     * 
     * @return References to all currently selected cells.
     */
    public Set<CellReference> getSelectedCellReferences() {
        SelectionChangeEvent event = selectionManager.getLatestSelectionEvent();
        if (event == null) {
            return new HashSet<CellReference>();
        } else {
            return event.getAllSelectedCells();
        }
    }

    /**
     * An event that is fired to registered listeners when the selected sheet
     * has been changed.
     */
    public static class SelectedSheetChangeEvent extends Component.Event {

        private final Sheet newSheet;
        private final Sheet previousSheet;
        private final int newSheetVisibleIndex;
        private final int newSheetPOIIndex;

        /**
         * Creates a new SelectedSheetChangeEvent.
         * 
         * @param source
         *            Spreadsheet that triggered the event
         * @param newSheet
         *            New selection
         * @param previousSheet
         *            Previous selection
         * @param newSheetVisibleIndex
         *            New visible index of selection
         * @param newSheetPOIIndex
         *            New POI index of selection
         */
        public SelectedSheetChangeEvent(Component source, Sheet newSheet,
                Sheet previousSheet, int newSheetVisibleIndex,
                int newSheetPOIIndex) {
            super(source);
            this.newSheet = newSheet;
            this.previousSheet = previousSheet;
            this.newSheetVisibleIndex = newSheetVisibleIndex;
            this.newSheetPOIIndex = newSheetPOIIndex;
        }

        /**
         * Gets the newly selected sheet.
         * 
         * @return The new selection
         */
        public Sheet getNewSheet() {
            return newSheet;
        }

        /**
         * Gets the sheet that was previously selected.
         * 
         * @return The previous selection
         */
        public Sheet getPreviousSheet() {
            return previousSheet;
        }

        /**
         * Gets the index of the newly selected sheet among all visible sheets.
         * 
         * @return Index of new selection among visible sheets
         */
        public int getNewSheetVisibleIndex() {
            return newSheetVisibleIndex;
        }

        /**
         * Gets the POI index of the newly selected sheet.
         * 
         * @return POI index of new selection
         */
        public int getNewSheetPOIIndex() {
            return newSheetPOIIndex;
        }
    }

    /**
     * A listener for when a sheet is selected.
     */
    public interface SelectedSheetChangeListener extends Serializable {
        public static final Method SELECTED_SHEET_CHANGE_METHOD = ReflectTools
                .findMethod(SelectedSheetChangeListener.class,
                        "onSelectedSheetChange", SelectedSheetChangeEvent.class);

        /**
         * This method is called an all registered listeners when the selected
         * sheet has changed.
         * 
         * @param event
         *            Sheet selection event
         */
        public void onSelectedSheetChange(SelectedSheetChangeEvent event);
    }

    /**
     * Adds the given SelectedSheetChangeListener to this Spreadsheet.
     * 
     * @param listener
     *            Listener to add
     */
    public void addSelectedSheetChangeListener(
            SelectedSheetChangeListener listener) {
        addListener(SelectedSheetChangeEvent.class, listener,
                SelectedSheetChangeListener.SELECTED_SHEET_CHANGE_METHOD);
    }

    /**
     * Removes the given SelectedSheetChangeListener from this Spreadsheet.
     * 
     * @param listener
     *            Listener to remove
     */
    public void removeSelectedSheetChangeListener(
            SelectedSheetChangeListener listener) {
        removeListener(SelectedSheetChangeEvent.class, listener,
                SelectedSheetChangeListener.SELECTED_SHEET_CHANGE_METHOD);
    }

    private void fireSelectedSheetChangeEvent(Sheet previousSheet,
            Sheet newSheet) {
        int newSheetPOIIndex = workbook.getActiveSheetIndex();

        fireEvent(new SelectedSheetChangeEvent(this, newSheet, previousSheet,
                getSpreadsheetSheetIndex(newSheetPOIIndex), newSheetPOIIndex));
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.HasComponents#iterator()
     */
    @Override
    public Iterator<Component> iterator() {
        if (customComponents == null && sheetPopupButtons == null) {
            List<Component> emptyList = Collections.emptyList();
            return emptyList.iterator();
        } else {
            return new SpreadsheetIterator<Component>(customComponents,
                    sheetPopupButtons);
        }
    }

    /**
     * Component iterator for components contained within the Spreadsheet:
     * CustomComponents and PopupButtons.
     */
    public static class SpreadsheetIterator<E extends Component> implements
            Iterator<Component> {
        private final Iterator<Component> customComponentIterator;
        private final Iterator<PopupButton> sheetPopupButtonIterator;
        /** true for customComponentIterator, false for sheetPopupButtonIterator */
        private boolean currentIteratorPointer;

        public SpreadsheetIterator(Set<Component> customComponents,
                Map<CellReference, PopupButton> sheetPopupButtons) {
            customComponentIterator = customComponents == null ? null
                    : customComponents.iterator();
            sheetPopupButtonIterator = sheetPopupButtons == null ? null
                    : sheetPopupButtons.values().iterator();
            currentIteratorPointer = true;
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.util.Iterator#hasNext()
         */
        @Override
        public boolean hasNext() {
            return (customComponentIterator != null && customComponentIterator
                    .hasNext())
                    || (sheetPopupButtonIterator != null && sheetPopupButtonIterator
                            .hasNext());
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.util.Iterator#next()
         */
        @Override
        public Component next() {
            if (customComponentIterator != null
                    && customComponentIterator.hasNext()) {
                return customComponentIterator.next();
            }
            if (sheetPopupButtonIterator != null
                    && sheetPopupButtonIterator.hasNext()) {
                currentIteratorPointer = false;
                return sheetPopupButtonIterator.next();
            }
            throw new NoSuchElementException();
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.util.Iterator#remove()
         */
        @Override
        public void remove() {
            if (currentIteratorPointer && customComponentIterator != null) {
                customComponentIterator.remove();
            } else if (sheetPopupButtonIterator != null) {
                sheetPopupButtonIterator.remove();
            }
        }
    }

    /**
     * This is called when the client-side connector has been initialized.
     */
    protected void onConnectorInit() {
        reloadCellDataOnNextScroll = true;
        valueManager.clearCachedContent();
    }

    /**
     * Reloads all data from the current spreadsheet and performs a full
     * re-render. <br/>
     * Functionally same as calling {@link #setWorkbook(Workbook)} with
     * {@link #getWorkbook()} parameter.
     */
    public void reload() {
        setWorkbook(getWorkbook());
    }

    /**
     * Sets the content of the info label.
     * 
     * @param value
     *            The new content. Can not be HTML.
     */
    public void setInfoLabelValue(String value) {
        getState().infoLabelValue = value;
    }

    /**
     * Gets the content of the info label
     * 
     * @return Current content of the info label.
     */
    public String getInfoLabelValue() {
        return getState().infoLabelValue;
    }

    /**
     * Selects the cell at the given coordinates
     * 
     * @param row
     *            Row index, 0-based
     * @param col
     *            Column index, 0-based
     */
    public void setSelection(int row, int col) {
        CellReference ref = new CellReference(row, col);
        selectionManager.handleCellSelection(ref);
    }

    /**
     * Selects the given range, using the cell at row1 and col1 as an anchor.
     * 
     * @param row1
     *            Index of the first row of the area, 0-based
     * @param col1
     *            Index of the first column of the area, 0-based
     * @param row2
     *            Index of the last row of the area, 0-based
     * @param col2
     *            Index of the last column of the area, 0-based
     */
    public void setSelectionRange(int row1, int col1, int row2, int col2) {
        CellReference ref = new CellReference(row1, col1);

        if (row1 == row2 && col1 == col2) {
            selectionManager.handleCellSelection(ref);
        } else {
            CellRangeAddress cra = new CellRangeAddress(row1, row2, col1, col2);
            selectionManager.handleCellRangeSelection(ref, cra);
        }
    }

    /**
     * Selects the cell(s) at the given coordinates
     * 
     * @param selectionRange
     *            The wanted range, e.g. "A3" or "B3:C5"
     */
    public void setSelection(String selectionRange) {
        selectionManager.handleCellRangeSelection(CellRangeAddress
                .valueOf(selectionRange));
    }

    /**
     * Gets the ConditionalFormatter
     * 
     * @return the {@link ConditionalFormatter} used by this {@link Spreadsheet}
     */
    public ConditionalFormatter getConditionalFormatter() {
        return conditionalFormatter;
    }

    /**
     * Disposes the current {@link Workbook}, if any, and loads a new empty XSLX
     * Workbook.
     * 
     * Note: Discards all data. Be sure to write out the old Workbook if needed.
     */
    public void reset() {
        SpreadsheetFactory.loadNewXLSXSpreadsheet(this);
        srcUri = null;
    }

    /* Attribute names for declarative format support. */
    private static final String ATTR_ACTIVE_SHEET = "active-sheet-index";
    private static final String ATTR_DEFAULT_COL_WIDTH = "default-column-width";
    private static final String ATTR_DEFAULT_COL_COUNT = "default-column-count";
    private static final String ATTR_DEFAULT_ROW_COUNT = "default-row-count";
    private static final String ATTR_DEFAULT_ROW_HEIGHT = "default-row-height";
    private static final String ATTR_NO_GRIDLINES = "no-gridlines";
    private static final String ATTR_NO_HEADINGS = "no-headings";
    private static final String ATTR_NO_FUNCTION_BAR = "no-function-bar";
    private static final String ATTR_NO_SHEET_SELECTION_BAR = "no-sheetselection-bar";
    private static final String ATTR_SRC = "src";
    private CommentAuthorProvider commentAuthorProvider;

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#readDesign(org.jsoup.nodes.Element,
     * com.vaadin.ui.declarative.DesignContext)
     */
    @Override
    public void readDesign(Element design, DesignContext designContext) {
        super.readDesign(design, designContext);

        Attributes attr = design.attributes();

        if (attr.hasKey(ATTR_SRC)) {
            String src = DesignAttributeHandler.readAttribute(ATTR_SRC, attr,
                    String.class);
            try {
                URL url = new URL(src);
                read(url.openStream());
                srcUri = src;
            } catch (MalformedURLException e) {
                LOGGER.log(Level.SEVERE, "Failed to parse the provided URI.", e);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        "Failed to read Excel file from provided URI.", e);
            }
        }
        if (attr.hasKey(ATTR_DEFAULT_COL_COUNT)) {
            Integer colCount = DesignAttributeHandler.readAttribute(
                    ATTR_DEFAULT_COL_COUNT, attr, Integer.class);
            setDefaultColumnCount(colCount);
        }
        if (attr.hasKey(ATTR_DEFAULT_COL_WIDTH)) {
            Integer colWidth = DesignAttributeHandler.readAttribute(
                    ATTR_DEFAULT_COL_WIDTH, attr, Integer.class);
            setDefaultColumnWidth(colWidth);
        }
        if (attr.hasKey(ATTR_DEFAULT_ROW_COUNT)) {
            Integer rowCount = DesignAttributeHandler.readAttribute(
                    ATTR_DEFAULT_ROW_COUNT, attr, Integer.class);
            setDefaultRowCount(rowCount);
        }
        if (attr.hasKey(ATTR_DEFAULT_ROW_HEIGHT)) {
            Float rowHeight = DesignAttributeHandler.readAttribute(
                    ATTR_DEFAULT_ROW_HEIGHT, attr, Float.class);
            setDefaultRowHeight(rowHeight);
        }
        if (attr.hasKey(ATTR_ACTIVE_SHEET)) {
            Integer activeSheet = DesignAttributeHandler.readAttribute(
                    ATTR_ACTIVE_SHEET, attr, Integer.class);
            setActiveSheetIndex(activeSheet);
        }
        if (attr.hasKey(ATTR_NO_GRIDLINES)) {
            Boolean noGridlines = DesignAttributeHandler.readAttribute(
                    ATTR_NO_GRIDLINES, attr, Boolean.class);
            setGridlinesVisible(!noGridlines);
        }
        if (attr.hasKey(ATTR_NO_HEADINGS)) {
            Boolean noHeadings = DesignAttributeHandler.readAttribute(
                    ATTR_NO_HEADINGS, attr, Boolean.class);
            setRowColHeadingsVisible(!noHeadings);
        }
        if (attr.hasKey(ATTR_NO_FUNCTION_BAR)) {
            Boolean hidden = DesignAttributeHandler.readAttribute(
                    ATTR_NO_FUNCTION_BAR, attr, Boolean.class);
            setFunctionBarVisible(!hidden);
        }
        if (attr.hasKey(ATTR_NO_SHEET_SELECTION_BAR)) {
            Boolean hidden = DesignAttributeHandler.readAttribute(
                    ATTR_NO_SHEET_SELECTION_BAR, attr, Boolean.class);
            setSheetSelectionBarVisible(!hidden);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#getCustomAttributes()
     */
    @Override
    protected Collection<String> getCustomAttributes() {
        Collection<String> result = super.getCustomAttributes();
        result.add(ATTR_ACTIVE_SHEET);
        result.add(ATTR_DEFAULT_COL_COUNT);
        result.add(ATTR_DEFAULT_COL_WIDTH);
        result.add(ATTR_DEFAULT_ROW_COUNT);
        result.add(ATTR_DEFAULT_ROW_HEIGHT);
        result.add(ATTR_NO_GRIDLINES);
        result.add(ATTR_NO_HEADINGS);
        result.add(ATTR_NO_FUNCTION_BAR);
        result.add(ATTR_NO_SHEET_SELECTION_BAR);
        result.add(ATTR_SRC);
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.vaadin.ui.AbstractComponent#writeDesign(org.jsoup.nodes.Element,
     * com.vaadin.ui.declarative.DesignContext)
     */
    @Override
    public void writeDesign(Element design, DesignContext designContext) {
        super.writeDesign(design, designContext);

        Attributes attr = design.attributes();

        DesignAttributeHandler.writeAttribute(ATTR_NO_GRIDLINES, attr,
                !isGridlinesVisible(), false, Boolean.class);

        DesignAttributeHandler.writeAttribute(ATTR_NO_HEADINGS, attr,
                !isRowColHeadingsVisible(), false, Boolean.class);

        DesignAttributeHandler.writeAttribute(ATTR_NO_FUNCTION_BAR, attr,
                !isFunctionBarVisible(), false, Boolean.class);

        DesignAttributeHandler.writeAttribute(ATTR_NO_SHEET_SELECTION_BAR,
                attr, !isSheetSelectionBarVisible(), false, Boolean.class);

        DesignAttributeHandler.writeAttribute(ATTR_ACTIVE_SHEET, attr,
                getActiveSheetIndex(), 0, Integer.class);

        DesignAttributeHandler.writeAttribute(ATTR_DEFAULT_COL_COUNT, attr,
                getDefaultColumnCount(), SpreadsheetFactory.DEFAULT_COLUMNS,
                Integer.class);

        DesignAttributeHandler.writeAttribute(ATTR_DEFAULT_ROW_COUNT, attr,
                getDefaultRowCount(), SpreadsheetFactory.DEFAULT_ROWS,
                Integer.class);

        if (defaultColWidthSet) {
            DesignAttributeHandler.writeAttribute(ATTR_DEFAULT_COL_WIDTH, attr,
                    getDefaultColumnWidth(),
                    SpreadsheetUtil.getDefaultColumnWidthInPx(), Integer.class);
        }

        if (defaultRowHeightSet) {
            DesignAttributeHandler.writeAttribute(ATTR_DEFAULT_ROW_HEIGHT,
                    attr, getDefaultRowHeight(),
                    SpreadsheetFactory.DEFAULT_ROW_HEIGHT_POINTS, Float.class);
        }

        if (srcUri != null) {
            DesignAttributeHandler.writeAttribute(ATTR_SRC, attr, srcUri, null,
                    String.class);
        }
    }

    /**
     * Returns the formatting string that is used when a user enters percentages
     * into the Spreadsheet.
     * <p>
     * Default is "0.00%".
     * 
     * @return The formatting applied to percentage values when entered by the
     *         user
     */
    public String getDefaultPercentageFormat() {
        return defaultPercentageFormat;
    }

    /**
     * Sets the formatting string that is used when a user enters percentages
     * into the Spreadsheet.
     * <p>
     * Default is "0.00%".
     */
    public void setDefaultPercentageFormat(String defaultPercentageFormat) {
        this.defaultPercentageFormat = defaultPercentageFormat;
    }

    /**
     * This interface can be implemented to provide the comment author name set
     * to new comments in cells.
     */
    public interface CommentAuthorProvider {

        /**
         * Gets the author name for a new comment about to be added to the cell
         * at the given cell reference.
         * 
         * @param targetCell
         *            Reference to the target cell
         * @return Comment author name
         */
        public String getAuthorForComment(CellReference targetCell);
    }

    /**
     * Sets the given CommentAuthorProvider to this Spreadsheet.
     * 
     * @param commentAuthorProvider
     *            New provider
     */
    public void setCommentAuthorProvider(
            CommentAuthorProvider commentAuthorProvider) {
        this.commentAuthorProvider = commentAuthorProvider;
    }

    /**
     * Gets the CommentAuthorProvider currently set to this Spreadsheet.
     * 
     * @return Current provider or null if not set.
     */
    public CommentAuthorProvider getCommentAuthorProvider() {
        return commentAuthorProvider;
    }

    /**
     * Triggers editing of the cell comment in the given cell reference. Note
     * that the cell must have a previously set cell comment in order to be able
     * to edit it.
     * 
     * @param cr
     *            Reference to the cell containing the comment to edit
     */
    public void editCellComment(CellReference cr) {
        getRpcProxy().editCellComment(cr.getCol(), cr.getRow());
    }

    /**
     * Sets the visibility of the top function bar. By default the bar is
     * visible.
     * 
     * @param functionBarVisible
     *            True to show the top bar, false to hide it.
     */
    public void setFunctionBarVisible(boolean functionBarVisible) {
        if (functionBarVisible) {
            removeStyleName(HIDE_FUNCTION_BAR_STYLE);
        } else {
            addStyleName(HIDE_FUNCTION_BAR_STYLE);
        }
    }

    /**
     * Gets the visibility of the top function bar. By default the bar is
     * visible.
     * 
     * @return True if the function bar is visible, false otherwise.
     */
    public boolean isFunctionBarVisible() {
        return !getStyleName().contains(HIDE_FUNCTION_BAR_STYLE);
    }

    /**
     * Sets the visibility of the bottom sheet selection bar. By default the bar
     * is visible.
     * 
     * @param sheetSelectionBarVisible
     *            True to show the sheet selection bar, false to hide it.
     */
    public void setSheetSelectionBarVisible(boolean sheetSelectionBarVisible) {
        if (sheetSelectionBarVisible) {
            removeStyleName(HIDE_TABSHEET_STYLE);
        } else {
            addStyleName(HIDE_TABSHEET_STYLE);
        }
    }

    /**
     * Gets the visibility of the bottom sheet selection bar. By default the bar
     * is visible.
     * 
     * @return True if the sheet selection bar is visible, false otherwise.
     */
    public boolean isSheetSelectionBarVisible() {
        return !getStyleName().contains(HIDE_TABSHEET_STYLE);
    }

    /**
     * Enables or disables the report style. When enabled, the top and bottom
     * bars of Spreadsheet will be hidden.
     * 
     * @param reportStyle
     *            True to hide both toolbars, false to show them.
     */
    public void setReportStyle(boolean reportStyle) {
        setFunctionBarVisible(!reportStyle);
        setSheetSelectionBarVisible(!reportStyle);
    }

    /**
     * Gets the state of the report style.
     * 
     * @return True if report style is enabled, false otherwise.
     */
    public boolean isReportStyle() {
        return !isSheetSelectionBarVisible() && !isFunctionBarVisible();
    }
}
