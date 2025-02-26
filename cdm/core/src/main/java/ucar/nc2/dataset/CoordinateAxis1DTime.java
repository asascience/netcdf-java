/*
 * Copyright (c) 1998-2020 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.dataset;

import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.Array;
import ucar.ma2.ArrayChar;
import ucar.ma2.ArrayObject;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.IndexIterator;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.nc2.Group;
import ucar.nc2.constants.AxisType;
import ucar.nc2.constants._Coordinate;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarDateFormatter;
import ucar.nc2.time.CalendarDateRange;
import ucar.nc2.units.TimeUnit;
import ucar.nc2.Dimension;
import ucar.nc2.Attribute;
import ucar.nc2.util.NamedAnything;
import ucar.nc2.util.NamedObject;
import java.io.IOException;
import ucar.nc2.units.DateRange;

/**
 * A 1-dimensional Coordinate Axis representing Calendar time.
 * Its coordinate values can be represented as Dates.
 * <p/>
 * May use udunit dates, or ISO Strings.
 *
 * @author caron
 */
public class CoordinateAxis1DTime extends CoordinateAxis1D {

  private static final Logger logger = LoggerFactory.getLogger(CoordinateAxis1DTime.class);

  public static CoordinateAxis1DTime factory(NetcdfDataset ncd, VariableDS org, Formatter errMessages)
      throws IOException {
    if (org instanceof CoordinateAxis1DTime)
      return (CoordinateAxis1DTime) org;

    if (org.getDataType() == DataType.CHAR)
      return new CoordinateAxis1DTime(ncd, org, errMessages, org.getDimension(0).getShortName());

    else if (org.getDataType() == DataType.STRING)
      return new CoordinateAxis1DTime(ncd, org, errMessages, org.getDimensionsString());

    else
      return new CoordinateAxis1DTime(ncd, org, errMessages);
  }


  ////////////////////////////////////////////////////////////////

  // for section and slice
  @Override
  protected CoordinateAxis1DTime copy() {
    return new CoordinateAxis1DTime(this.ncd, this);
  }

  /** @deprecated Use CoordinateAxis1DTime.toBuilder() */
  @Deprecated
  // copy constructor
  private CoordinateAxis1DTime(NetcdfDataset ncd, CoordinateAxis1DTime org) {
    super(ncd, org);
    helper = org.helper;
    this.cdates = org.cdates;
  }

  @Override
  public CoordinateAxis1DTime section(Range r) throws InvalidRangeException {
    CoordinateAxis1DTime s = (CoordinateAxis1DTime) super.section(r);
    List<CalendarDate> cdates = getCalendarDates();

    List<CalendarDate> cdateSection = new ArrayList<>(cdates.size());
    for (int idx : r)
      cdateSection.add(cdates.get(idx));

    s.cdates = cdateSection;
    return s;
  }

  /**
   * Get the the ith CalendarDate.
   *
   * @param idx index
   * @return the ith CalendarDate
   */
  public CalendarDate getCalendarDate(int idx) {
    List<CalendarDate> cdates = getCalendarDates(); // in case we want to lazily evaluate
    return cdates.get(idx);
  }

  /**
   * Get calendar date range
   *
   * @return calendar date range
   */
  public CalendarDateRange getCalendarDateRange() {
    List<CalendarDate> cd = getCalendarDates();
    int last = cd.size();
    return (last > 0) ? CalendarDateRange.of(cd.get(0), cd.get(last - 1)) : null;
  }

  @Override
  public List<NamedObject> getNames() {
    List<CalendarDate> cdates = getCalendarDates();
    List<NamedObject> names = new ArrayList<>(cdates.size());
    for (CalendarDate cd : cdates)
      names.add(new NamedAnything(CalendarDateFormatter.toDateTimeStringISO(cd), getShortName())); // "calendar date"));
    return names;
  }

  /**
   * only if isRegular() LOOK REDO
   *
   * @return time unit
   * @throws Exception on bad unit string
   */
  public TimeUnit getTimeResolution() throws Exception {
    String tUnits = getUnitsString();
    StringTokenizer stoker = new StringTokenizer(tUnits);
    double tResolution = getIncrement();
    return new TimeUnit(tResolution, stoker.nextToken());
  }

  /**
   * Given a Date, find the corresponding time index on the time coordinate axis.
   * Can only call this is hasDate() is true.
   * This will return
   * <ul>
   * <li>i, if time(i) <= d < time(i+1).
   * <li>0, if d < time(0)
   * <li>n-1, if d > time(n-1), where n is length of time coordinates
   * </ul>
   *
   * @param d date to look for
   * @return corresponding time index on the time coordinate axis
   * @throws UnsupportedOperationException is no time axis or isDate() false
   */
  public int findTimeIndexFromCalendarDate(CalendarDate d) {
    List<CalendarDate> cdates = getCalendarDates(); // LOOK linear search, switch to binary
    int index = 0;
    while (index < cdates.size()) {
      if (d.compareTo(cdates.get(index)) < 0)
        break;
      index++;
    }
    return Math.max(0, index - 1);
  }

  /**
   * See if the given CalendarDate appears as a coordinate
   *
   * @param date test this
   * @return true if equals a coordinate
   */
  public boolean hasCalendarDate(CalendarDate date) {
    List<CalendarDate> cdates = getCalendarDates();
    for (CalendarDate cd : cdates) { // LOOK linear search, switch to binary
      if (date.equals(cd))
        return true;
    }
    return false;
  }

  /**
   * Get the list of datetimes in this coordinate as CalendarDate objects.
   *
   * @return list of CalendarDates.
   */
  public List<CalendarDate> getCalendarDates() {
    return cdates;
  }

  public CalendarDate[] getCoordBoundsDate(int i) {
    double[] intv = getCoordBounds(i);
    CalendarDate[] e = new CalendarDate[2];
    e[0] = helper.makeCalendarDateFromOffset(intv[0]);
    e[1] = helper.makeCalendarDateFromOffset(intv[1]);
    return e;
  }

  public CalendarDate getCoordBoundsMidpointDate(int i) {
    double[] intv = getCoordBounds(i);
    double midpoint = (intv[0] + intv[1]) / 2;
    return helper.makeCalendarDateFromOffset(midpoint);
  }

  @Override
  protected void readValues() {
    // if orgVar DataType is not numeric (e.g. Char or String), read from the cdates array that was created when
    // the axis was created by this classes factory.
    if (this.orgDataType != null && !this.orgDataType.isNumeric()) {
      this.coords = cdates.stream().mapToDouble(cdate -> (double) cdate.getDifferenceInMsecs(cdates.get(0))).toArray();
      // make sure parent methods do not try to read from the orgVar again
      this.wasRead = true;
    } else {
      super.readValues();
    }
  }

  ////////////////////////////////////////////////////////////////////////

  /**
   * Constructor for CHAR or STRING variables.
   * Must be ISO dates.
   *
   * @param ncd the containing dataset
   * @param org the underlying Variable
   * @param errMessages put error messages here; may be null
   * @param dims list of dimensions
   * @throws IOException on read error
   * @throws IllegalArgumentException if cant convert coordinate values to a Date
   * @deprecated Use CoordinateAxis1DTime.builder()
   */
  @Deprecated
  private CoordinateAxis1DTime(NetcdfDataset ncd, VariableDS org, Formatter errMessages, String dims)
      throws IOException {
    // Although we are dealing with a string or char variable, we're going to make a numeric valued time
    // coordinate axis, as long as we can transform the string values into a UDUNITS time value (e.g.
    // transform an ISO date/time string into something like "seconds since 1970-01-01 00:00:00 UTC)
    super(ncd, org.getParentGroupOrRoot(), org.getShortName(), DataType.DOUBLE, dims, org.getUnitsString(),
        org.getDescription());

    // Need to set the original var its DataType, that way we can treat this coordinate axis as numeric properly
    // when we override the readValues() method and encounter a 1D time coordinate axis backing variable that is
    // of type String.
    this.orgVar = org;
    this.orgDataType = org.getDataType();
    this.orgName = org.orgName;
    this.helper = new CoordinateAxisTimeHelper(getCalendarFromAttribute(), null);

    if (org.getDataType() == DataType.CHAR)
      cdates = makeTimesFromChar(org, errMessages);
    else
      cdates = makeTimesFromStrings(org, errMessages);

    for (Attribute att : org.attributes()) {
      addAttribute(att);
    }

    // look for _CoordinateAxisType attribute and use it if it is time or runtime
    Attribute coordAxisTypeAttr = org.attributes().findAttributeIgnoreCase(_Coordinate.AxisType);
    String attributeTypeName = coordAxisTypeAttr != null ? coordAxisTypeAttr.getStringValue() : null;
    if (attributeTypeName != null) {
      if (attributeTypeName.equalsIgnoreCase(AxisType.Time.name())
          || attributeTypeName.equalsIgnoreCase(AxisType.RunTime.name())) {
        this.axisType = AxisType.getType(attributeTypeName);
      } else {
        logger.info("Attribute {} on variable {} is not a recognized time axis type.", _Coordinate.AxisType,
            org.getFullName());
      }
    }
    this.setUnitsString("milliseconds since " + cdates.get(0).toString());
  }

  private List<CalendarDate> makeTimesFromChar(VariableDS org, Formatter errMessages) throws IOException {
    int ncoords = (int) org.getSize();
    int rank = org.getRank();
    int strlen = org.getShape(rank - 1);
    ncoords /= strlen;

    List<CalendarDate> result = new ArrayList<>(ncoords);

    ArrayChar data = (ArrayChar) org.read();
    ArrayChar.StringIterator ii = data.getStringIterator();
    ArrayObject.D1 sdata = (ArrayObject.D1) Array.factory(DataType.STRING, new int[] {ncoords});

    for (int i = 0; i < ncoords; i++) {
      String coordValue = ii.next();
      CalendarDate cd = makeCalendarDateFromStringCoord(coordValue, org, errMessages);
      sdata.set(i, coordValue);
      result.add(cd);
    }
    setCachedData(sdata, true);
    return result;
  }

  private List<CalendarDate> makeTimesFromStrings(VariableDS org, Formatter errMessages) throws IOException {

    int ncoords = (int) org.getSize();
    List<CalendarDate> result = new ArrayList<>(ncoords);

    ArrayObject data = (ArrayObject) org.read();
    IndexIterator ii = data.getIndexIterator();
    for (int i = 0; i < ncoords; i++) {
      String coordValue = (String) ii.getObjectNext();
      CalendarDate cd = makeCalendarDateFromStringCoord(coordValue, org, errMessages);
      result.add(cd);
    }

    return result;
  }

  private CalendarDate makeCalendarDateFromStringCoord(String coordValue, VariableDS org, Formatter errMessages) {
    CalendarDate cd = helper.makeCalendarDateFromOffset(coordValue);
    if (cd == null) {
      if (errMessages != null) {
        errMessages.format("String time coordinate must be ISO formatted= %s%n", coordValue);
        logger.info("Char time coordinate must be ISO formatted= {} file = {}", coordValue, org.getDatasetLocation());
      }
      throw new IllegalArgumentException();
    }
    return cd;
  }


  /**
   * Constructor for numeric values - must have units
   *
   * @param ncd the containing dataset
   * @param org the underlying Variable
   * @throws IOException on read error
   * @deprecated Use CoordinateAxis1DTime.builder()
   */
  @Deprecated
  private CoordinateAxis1DTime(NetcdfDataset ncd, VariableDS org, Formatter errMessages) throws IOException {
    super(ncd, org);
    this.helper = new CoordinateAxisTimeHelper(getCalendarFromAttribute(), getUnitsString());

    // make the coordinates
    int ncoords = (int) org.getSize();
    List<CalendarDate> result = new ArrayList<>(ncoords);

    Array data = org.read();

    int count = 0;
    IndexIterator ii = data.getIndexIterator();
    for (int i = 0; i < ncoords; i++) {
      double val = ii.getDoubleNext();
      if (Double.isNaN(val))
        continue; // WTF ??
      result.add(helper.makeCalendarDateFromOffset(val));
      count++;
    }

    // if we encountered NaNs, shorten it up
    if (count != ncoords) {
      Dimension localDim = Dimension.builder(getShortName(), count).setIsShared(false).build();
      setDimension(0, localDim);

      // set the shortened values
      Array shortData = Array.factory(data.getDataType(), new int[] {count});
      Index ima = shortData.getIndex();
      int count2 = 0;
      ii = data.getIndexIterator();
      for (int i = 0; i < ncoords; i++) {
        double val = ii.getDoubleNext();
        if (Double.isNaN(val))
          continue;
        shortData.setDouble(ima.set0(count2), val);
        count2++;
      }

      // we have to decouple from the original variable
      cache.reset();
      setCachedData(shortData, true);
    }

    cdates = result;
  }

  ///////////////////////////////////////////////////////

  /**
   * Does not handle non-standard Calendars
   *
   * @deprecated use getCalendarDates() to correctly interpret calendars
   */
  public java.util.Date[] getTimeDates() {
    List<CalendarDate> cdates = getCalendarDates();
    Date[] timeDates = new Date[cdates.size()];
    int index = 0;
    for (CalendarDate cd : cdates)
      timeDates[index++] = cd.toDate();
    return timeDates;
  }

  /**
   * Does not handle non-standard Calendars
   *
   * @deprecated use getCalendarDate()
   */
  public java.util.Date getTimeDate(int idx) {
    return getCalendarDate(idx).toDate();
  }

  /**
   * Does not handle non-standard Calendars
   *
   * @deprecated use getCalendarDateRange()
   */
  public DateRange getDateRange() {
    CalendarDateRange cdr = getCalendarDateRange();
    return cdr.toDateRange();
  }

  /**
   * Does not handle non-standard Calendars
   *
   * @deprecated use findTimeIndexFromCalendarDate
   */
  public int findTimeIndexFromDate(java.util.Date d) {
    return findTimeIndexFromCalendarDate(CalendarDate.of(d));
  }

  /**
   * Does not handle non-standard Calendars
   *
   * @deprecated use hasCalendarDate
   */
  public boolean hasTime(Date date) {
    List<CalendarDate> cdates = getCalendarDates();
    for (CalendarDate cd : cdates) {
      if (date.equals(cd.toDate()))
        return true;
    }
    return false;
  }

  ////////////////////////////////////////////////////////////////////////////////////////////
  private CoordinateAxisTimeHelper helper;
  private List<CalendarDate> cdates;

  protected CoordinateAxis1DTime(Builder<?> builder, Group parentGroup) {
    super(builder, parentGroup);
  }

  public Builder<?> toBuilder() {
    return addLocalFieldsToBuilder(builder());
  }

  // Add local fields to the passed - in builder.
  protected Builder<?> addLocalFieldsToBuilder(Builder<? extends Builder<?>> b) {
    return (Builder<?>) super.addLocalFieldsToBuilder(b);
  }

  /**
   * Get Builder for this class that allows subclassing.
   *
   * @see "https://community.oracle.com/blogs/emcmanus/2010/10/24/using-builder-pattern-subclasses"
   */
  public static Builder<?> builder() {
    return new Builder2();
  }

  private static class Builder2 extends Builder<Builder2> {
    @Override
    protected Builder2 self() {
      return this;
    }
  }

  public static abstract class Builder<T extends Builder<T>> extends CoordinateAxis1D.Builder<T> {
    private boolean built;

    protected abstract T self();

    public CoordinateAxis1DTime build(Group parentGroup) {
      if (built)
        throw new IllegalStateException("already built");
      built = true;
      return new CoordinateAxis1DTime(this, parentGroup);
    }
  }
}
