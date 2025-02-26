/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.iosp.hdf5;

import static ucar.nc2.NetcdfFile.IOSP_MESSAGE_GET_NETCDF_FILE_FORMAT;

import java.nio.charset.StandardCharsets;
import ucar.nc2.constants.DataFormatType;
import ucar.ma2.*;
import ucar.nc2.constants.CDM;
import ucar.nc2.iosp.netcdf3.N3iosp;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.write.NetcdfFileFormat;
import ucar.unidata.io.RandomAccessFile;
import ucar.nc2.iosp.*;
import ucar.nc2.iosp.hdf4.HdfEos;
import ucar.nc2.iosp.hdf4.H4header;
import ucar.nc2.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Formatter;

/**
 * HDF5 I/O
 *
 * @author caron
 */

public class H5iosp extends AbstractIOServiceProvider {
  public static final String IOSP_MESSAGE_INCLUDE_ORIGINAL_ATTRIBUTES = "IncludeOrgAttributes";

  public static final int VLEN_T_SIZE = 16; // Appears to be no way to compute on the fly.

  static boolean debug;
  static boolean debugPos;
  static boolean debugHeap;
  static boolean debugHeapStrings;
  static boolean debugFilter;
  static boolean debugRead;
  static boolean debugFilterIndexer;
  static boolean debugChunkIndexer;
  static boolean debugVlen;
  static boolean debugStructure;
  static boolean useHdfEos = true;

  static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(H5iosp.class);

  public static void setDebugFlags(ucar.nc2.util.DebugFlags debugFlag) {
    debug = debugFlag.isSet("H5iosp/read");
    debugPos = debugFlag.isSet("H5iosp/filePos");
    debugHeap = debugFlag.isSet("H5iosp/Heap");
    debugFilter = debugFlag.isSet("H5iosp/filter");
    debugFilterIndexer = debugFlag.isSet("H5iosp/filterIndexer");
    debugChunkIndexer = debugFlag.isSet("H5iosp/chunkIndexer");
    debugVlen = debugFlag.isSet("H5iosp/vlen");

    H5header.setDebugFlags(debugFlag);
    H4header.setDebugFlags(debugFlag);
    if (debugFilter)
      H5tiledLayoutBB.debugFilter = debugFilter;

  }

  public boolean isValidFile(ucar.unidata.io.RandomAccessFile raf) throws IOException {
    return H5header.isValidFile(raf);
  }

  public String getFileTypeId() {
    if (isEos)
      return "HDF5-EOS";
    if (headerParser.isNetcdf4())
      return DataFormatType.NETCDF4.getDescription();
    return DataFormatType.HDF5.getDescription();
  }

  public String getFileTypeDescription() {
    return "Hierarchical Data Format, version 5";
  }

  public void getEosInfo(Formatter f) throws IOException {
    NetcdfFile ncfile = headerParser.ncfile;
    Group eosInfo = ncfile.getRootGroup().findGroupLocal(HdfEos.HDF5_GROUP);
    if (eosInfo != null) {
      HdfEos.getEosInfo(ncfile, eosInfo, f);
    } else {
      f.format("Cant find GROUP '%s'", HdfEos.HDF5_GROUP);
    }
  }

  public static void useHdfEos(boolean val) {
    useHdfEos = val;
  }

  //////////////////////////////////////////////////////////////////////////////////

  // private RandomAccessFile raf;
  private H5header headerParser;
  private boolean isEos;
  boolean includeOriginalAttributes;

  /////////////////////////////////////////////////////////////////////////////
  // reading

  public void open(RandomAccessFile raf, ucar.nc2.NetcdfFile ncfile, ucar.nc2.util.CancelTask cancelTask)
      throws IOException {
    super.open(raf, ncfile, cancelTask);
    headerParser = new H5header(this.raf, ncfile, this);
    headerParser.read(null);

    // check if its an HDF5-EOS file
    Group eosInfo = ncfile.getRootGroup().findGroupLocal(HdfEos.HDF5_GROUP);
    if (eosInfo != null && useHdfEos) {
      isEos = HdfEos.amendFromODL(ncfile, eosInfo);
    }

    ncfile.finish();
  }

  public Array readData(ucar.nc2.Variable v2, Section section) throws IOException, InvalidRangeException {
    H5header.Vinfo vinfo = (H5header.Vinfo) v2.getSPobject();
    if (debugRead)
      System.out.printf("%s read %s%n", v2.getFullName(), section);
    return readData(v2, vinfo.dataPos, section);
  }

  // all the work is here, so can be called recursively
  private Array readData(ucar.nc2.Variable v2, long dataPos, Section wantSection)
      throws IOException, InvalidRangeException {
    H5header.Vinfo vinfo = (H5header.Vinfo) v2.getSPobject();
    DataType dataType = v2.getDataType();
    Object data;
    Layout layout;

    if (vinfo.useFillValue) { // fill value only
      Object pa = IospHelper.makePrimitiveArray((int) wantSection.computeSize(), dataType, vinfo.getFillValue());
      if (dataType == DataType.CHAR)
        pa = IospHelper.convertByteToChar((byte[]) pa);
      return Array.factory(dataType, wantSection.getShape(), pa);
    }

    if (vinfo.mfp != null) { // filtered
      if (debugFilter)
        System.out.println("read variable filtered " + v2.getFullName() + " vinfo = " + vinfo);
      assert vinfo.isChunked;
      ByteOrder bo = (vinfo.typeInfo.endian == 0) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
      layout = new H5tiledLayoutBB(v2, wantSection, raf, vinfo.mfp.getFilters(), bo);
      if (vinfo.typeInfo.isVString) {
        data = readFilteredStringData((LayoutBB) layout);
      } else {
        data = IospHelper.readDataFill((LayoutBB) layout, v2.getDataType(), vinfo.getFillValue());
      }

    } else { // normal case
      if (debug)
        System.out.println("read variable " + v2.getFullName() + " vinfo = " + vinfo);

      DataType readDtype = v2.getDataType();
      int elemSize = v2.getElementSize();
      Object fillValue = vinfo.getFillValue();
      int endian = vinfo.typeInfo.endian;

      // fill in the wantSection
      wantSection = Section.fill(wantSection, v2.getShape());

      if (vinfo.typeInfo.hdfType == 2) { // time
        readDtype = vinfo.mdt.timeType;
        elemSize = readDtype.getSize();
        fillValue = N3iosp.getFillValueDefault(readDtype);

      } else if (vinfo.typeInfo.hdfType == 8) { // enum
        H5header.TypeInfo baseInfo = vinfo.typeInfo.base;
        readDtype = baseInfo.dataType;
        elemSize = readDtype.getSize();
        fillValue = N3iosp.getFillValueDefault(readDtype);
        endian = baseInfo.endian;

      } else if (vinfo.typeInfo.hdfType == 9) { // vlen
        elemSize = vinfo.typeInfo.byteSize;
        endian = vinfo.typeInfo.endian;
        // wantSection = wantSection.removeVlen(); // remove vlen dimension
      }

      if (vinfo.isChunked) {
        layout = new H5tiledLayout((H5header.Vinfo) v2.getSPobject(), readDtype, wantSection);
      } else {
        layout = new LayoutRegular(dataPos, elemSize, v2.getShape(), wantSection);
      }
      data = readData(vinfo, v2, layout, readDtype, wantSection.getShape(), fillValue, endian);
    }

    if (data instanceof Array)
      return (Array) data;
    else if (dataType == DataType.STRUCTURE)
      return convertStructure((Structure) v2, layout, wantSection.getShape(), (byte[]) data); // LOOK
    else
      return Array.factory(dataType, wantSection.getShape(), data);
  }

  public String[] readFilteredStringData(LayoutBB layout) throws java.io.IOException {
    int size = (int) layout.getTotalNelems();
    String[] sa = new String[size];
    while (layout.hasNext()) {
      LayoutBB.Chunk chunk = layout.next();
      ByteBuffer bb = chunk.getByteBuffer();
      // bb.position(chunk.getSrcElem());
      if (debugHeapStrings)
        System.out.printf("readFilteredStringData chunk=%s%n", chunk);
      int destPos = (int) chunk.getDestElem();
      for (int i = 0; i < chunk.getNelems(); i++) { // 16 byte "heap ids"
        sa[destPos++] = headerParser.readHeapString(bb, (chunk.getSrcElem() + i) * 16); // LOOK does this handle section
                                                                                        // correctly ??
      }
    }
    return sa;
  }

  /*
   * Read data subset from file for a variable, return Array or java primitive array.
   *
   * @param v the variable to read.
   * 
   * @param layout handles skipping around in the file.
   * 
   * @param dataType dataType of the data to read
   * 
   * @param shape the shape of the output
   * 
   * @param fillValue fill value as a wrapped primitive
   * 
   * @return primitive array or Array with data read in
   * 
   * @throws java.io.IOException if read error
   * 
   * @throws ucar.ma2.InvalidRangeException if invalid section
   */
  private Object readData(H5header.Vinfo vinfo, Variable v, Layout layout, DataType dataType, int[] shape,
      Object fillValue, int endian) throws java.io.IOException, InvalidRangeException {

    H5header.TypeInfo typeInfo = vinfo.typeInfo;

    // special processing
    if (typeInfo.hdfType == 2) { // time
      Object data = IospHelper.readDataFill(raf, layout, dataType, fillValue, endian, true);
      Array timeArray = Array.factory(dataType, shape, data);

      // now transform into an ISO Date String
      String[] stringData = new String[(int) timeArray.getSize()];
      int count = 0;
      while (timeArray.hasNext()) {
        long time = timeArray.nextLong();
        stringData[count++] = CalendarDate.of(time).toString();
      }
      return Array.factory(DataType.STRING, shape, stringData);
    }

    if (typeInfo.hdfType == 8) { // enum
      Object data = IospHelper.readDataFill(raf, layout, dataType, fillValue, endian);
      return Array.factory(dataType, shape, data);
    }

    if (typeInfo.isVlen) { // vlen (not string)
      DataType readType = dataType;
      if (typeInfo.base.hdfType == 7) // reference
        readType = DataType.LONG;

      // general case is to read an array of vlen objects
      // each vlen generates an Array - so return ArrayObject of Array
      // boolean scalar = false; // layout.getTotalNelems() == 1; // if scalar, return just the len Array // remove
      // 12/25/10 jcaron
      Array[] data = new Array[(int) layout.getTotalNelems()];
      int count = 0;
      while (layout.hasNext()) {
        Layout.Chunk chunk = layout.next();
        if (chunk == null)
          continue;
        for (int i = 0; i < chunk.getNelems(); i++) {
          long address = chunk.getSrcPos() + layout.getElemSize() * i;
          Array vlenArray = headerParser.getHeapDataArray(address, readType, endian);
          data[count++] = (typeInfo.base.hdfType == 7) ? convertReference(vlenArray) : vlenArray;
        }
      }
      int prefixrank = 0;
      for (int i = 0; i < shape.length; i++) { // find leftmost vlen
        if (shape[i] < 0) {
          prefixrank = i;
          break;
        }
      }
      Array result;
      if (prefixrank == 0) // if scalar, return just the singleton vlen array
        result = data[0];
      else {
        int[] newshape = new int[prefixrank];
        System.arraycopy(shape, 0, newshape, 0, prefixrank);
        // result = Array.makeObjectArray(readType, data[0].getClass(), newshape, data);
        result = Array.makeVlenArray(newshape, data);
      }

      /*
       * else if (prefixrank == 1) // LOOK cant these two cases be combines - just differ in shape ??
       * result = Array.makeObjectArray(readType, data[0].getClass(), new int[]{count}, data);
       * else { // LOOK cant these two cases be combines - just differ in shape ??
       * // Otherwise create and fill in an n-dimensional Array Of Arrays
       * int[] newshape = new int[prefixrank];
       * System.arraycopy(shape, 0, newshape, 0, prefixrank);
       * Array ndimarray = Array.makeObjectArray(readType, Array.class, newshape, null);
       * // Transfer the elements of data into the n-dim arrays
       * IndexIterator iter = ndimarray.getIndexIterator();
       * for(int i = 0;iter.hasNext();i++) {
       * iter.setObjectNext(data[i]);
       * }
       * result = ndimarray;
       * }
       */
      // return (scalar) ? data[0] : new ArrayObject(data[0].getClass(), shape, data);
      // return new ArrayObject(data[0].getClass(), shape, data);
      return result;
    }

    if (dataType == DataType.STRUCTURE) { // LOOK what about subset ?
      int recsize = layout.getElemSize();
      long size = recsize * layout.getTotalNelems();
      byte[] byteArray = new byte[(int) size];
      while (layout.hasNext()) {
        Layout.Chunk chunk = layout.next();
        if (chunk == null)
          continue;
        if (debugStructure)
          System.out.println(
              " readStructure " + v.getFullName() + " chunk= " + chunk + " index.getElemSize= " + layout.getElemSize());
        // copy bytes directly into the underlying byte[] LOOK : assumes contiguous layout ??
        raf.seek(chunk.getSrcPos());
        raf.readFully(byteArray, (int) chunk.getDestElem() * recsize, chunk.getNelems() * recsize);
      }

      // place data into an ArrayStructureBB
      return convertStructure((Structure) v, layout, shape, byteArray); // LOOK
    }

    // normal case
    return readDataPrimitive(layout, dataType, shape, fillValue, endian, true);
  }

  Array convertReference(Array refArray) throws java.io.IOException {
    int nelems = (int) refArray.getSize();
    Index ima = refArray.getIndex();
    String[] result = new String[nelems];
    for (int i = 0; i < nelems; i++) {
      long reference = refArray.getLong(ima.set(i));
      String name = headerParser.getDataObjectName(reference);
      result[i] = name != null ? name : Long.toString(reference);
      if (debugVlen)
        System.out.printf(" convertReference 0x%x to %s %n", reference, result[i]);
    }
    return Array.factory(DataType.STRING, new int[] {nelems}, result);
  }

  private ArrayStructure convertStructure(Structure s, Layout layout, int[] shape, byte[] byteArray)
      throws IOException, InvalidRangeException {
    // create StructureMembers - must set offsets
    StructureMembers sm = s.makeStructureMembers();
    int calcSize = ArrayStructureBB.setOffsets(sm); // standard

    // special offset setting
    boolean hasHeap = convertStructure(s, sm);

    int recSize = layout.getElemSize();
    if (recSize < calcSize) {
      log.error("calcSize = {} actualSize = {}%n", calcSize, recSize);
      throw new IOException("H5iosp illegal structure size " + s.getFullName());
    }
    sm.setStructureSize(recSize);

    // place data into an ArrayStructureBB
    ByteBuffer bb = ByteBuffer.wrap(byteArray);
    ArrayStructureBB asbb = new ArrayStructureBB(sm, shape, bb, 0);

    // strings and vlens are stored on the heap, and must be read separately
    if (hasHeap) {
      int destPos = 0;
      for (int i = 0; i < layout.getTotalNelems(); i++) { // loop over each structure
        convertHeap(asbb, destPos, sm);
        destPos += layout.getElemSize();
      }
    }
    return asbb;
  }

  // recursive
  private boolean convertStructure(Structure s, StructureMembers sm) {
    boolean hasHeap = false;
    for (StructureMembers.Member m : sm.getMembers()) {
      Variable v2 = s.findVariable(m.getName());
      assert v2 != null;
      H5header.Vinfo vm = (H5header.Vinfo) v2.getSPobject();

      // apparently each member may have seperate byte order (!!!??)
      if (vm.typeInfo.endian >= 0)
        m.setDataObject(
            vm.typeInfo.endian == RandomAccessFile.LITTLE_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

      // vm.dataPos : offset since start of Structure
      m.setDataParam((int) vm.dataPos);

      // track if there is a heap
      if (v2.getDataType() == DataType.STRING || v2.isVariableLength())
        hasHeap = true;

      // recurse
      if (v2 instanceof Structure) {
        Structure nested = (Structure) v2;
        StructureMembers nestSm = nested.makeStructureMembers();
        m.setStructureMembers(nestSm);
        hasHeap |= convertStructure(nested, nestSm);
      }
    }
    return hasHeap;
  }

  /*
   * public static int setOffsets(StructureMembers members) {
   * int offset = 0;
   * for (StructureMembers.Member m : members.getMembers()) {
   * m.setDataParam(offset);
   * offset += m.getSizeBytes();
   * 
   * // set inner offsets (starts again at 0)
   * if (m.getStructureMembers() != null)
   * setOffsets(m.getStructureMembers());
   * }
   * members.setStructureSize(offset);
   * return offset;
   * }
   */

  void convertHeap(ArrayStructureBB asbb, int pos, StructureMembers sm)
      throws java.io.IOException, InvalidRangeException {
    ByteBuffer bb = asbb.getByteBuffer();
    for (StructureMembers.Member m : sm.getMembers()) {
      if (m.getDataType() == DataType.STRING) {
        m.setDataObject(ByteOrder.nativeOrder()); // the index is always written in "native order"
        int size = m.getSize();
        int destPos = pos + m.getDataParam();
        String[] result = new String[size];
        for (int i = 0; i < size; i++)
          result[i] = headerParser.readHeapString(bb, destPos + i * 16); // 16 byte "heap ids" are in the ByteBuffer

        int index = asbb.addObjectToHeap(result);
        bb.order(ByteOrder.nativeOrder()); // the string index is always written in "native order"
        bb.putInt(destPos, index); // overwrite with the index into the StringHeap

      } else if (m.isVariableLength()) {
        int startPos = pos + m.getDataParam();
        bb.order(ByteOrder.LITTLE_ENDIAN);

        ByteOrder bo = (ByteOrder) m.getDataObject();
        int endian = bo.equals(ByteOrder.LITTLE_ENDIAN) ? RandomAccessFile.LITTLE_ENDIAN : RandomAccessFile.BIG_ENDIAN;
        // Compute rank and size upto the first (and ideally last) VLEN
        int[] fieldshape = m.getShape();
        int prefixrank = 0;
        int size = 1;
        for (; prefixrank < fieldshape.length; prefixrank++) {
          if (fieldshape[prefixrank] < 0)
            break;
          size *= fieldshape[prefixrank];
        }
        assert size == m.getSize() : "Internal error: field size mismatch";
        Array[] fieldarray = new Array[size]; // hold all the vlen instance data
        // destPos will point to each vlen instance in turn
        // assuming we have 'size' such instances in a row.
        int destPos = startPos;
        for (int i = 0; i < size; i++) {
          // vlenarray extracts the i'th vlen contents (struct not supported).
          Array vlenArray = headerParser.readHeapVlen(bb, destPos, m.getDataType(), endian);
          fieldarray[i] = vlenArray;
          destPos += VLEN_T_SIZE; // Apparentlly no way to compute VLEN_T_SIZE on the fly
        }
        Array result;
        if (prefixrank == 0) // if scalar, return just the singleton vlen array
          result = fieldarray[0];
        else {
          int[] newshape = new int[prefixrank];
          System.arraycopy(fieldshape, 0, newshape, 0, prefixrank);
          // result = Array.makeObjectArray(m.getDataType(), fieldarray[0].getClass(), newshape, fieldarray);
          result = Array.makeVlenArray(newshape, fieldarray);
        }

        /*
         * if (prefixrank == 1)
         * result = Array.makeObjectArray(m.getDataType(), fieldarray[0].getClass(), new int[]{size}, fieldarray);
         * else {
         * // Otherwise create and fill in an n-dimensional Array Of Arrays
         * int[] newshape = new int[prefixrank];
         * System.arraycopy(fieldshape, 0, newshape, 0, prefixrank);
         * Array ndimarray = Array.makeObjectArray(m.getDataType(), Array.class, newshape, null);
         * // Transfer the elements of data into the n-dim arrays
         * IndexIterator iter = ndimarray.getIndexIterator();
         * for(int i = 0;iter.hasNext();i++) {
         * iter.setObjectNext(fieldarray[i]);
         * }
         * result = ndimarray;
         * }
         */

        // Array vlenArray = headerParser.readHeapVlen(bb, destPos, m.getDataType(), endian);

        int index = asbb.addObjectToHeap(result);
        bb.order(ByteOrder.nativeOrder());
        bb.putInt(startPos, index); // overwrite with the index into the Heap
      }
    }
  }

  /*
   * Read data subset from file for a variable, create primitive array.
   *
   * @param layout handles skipping around in the file.
   * 
   * @param dataType dataType of the variable
   * 
   * @param shape the shape of the output
   * 
   * @param fillValue fill value as a wrapped primitive
   * 
   * @param endian byte order
   * 
   * @return primitive array with data read in
   * 
   * @throws java.io.IOException if read error
   * 
   * @throws ucar.ma2.InvalidRangeException if invalid section
   */
  Object readDataPrimitive(Layout layout, DataType dataType, int[] shape, Object fillValue, int endian,
      boolean convertChar) throws java.io.IOException {

    if (dataType == DataType.STRING) {
      int size = (int) layout.getTotalNelems();
      String[] sa = new String[size];
      int count = 0;
      while (layout.hasNext()) {
        Layout.Chunk chunk = layout.next();
        if (chunk == null)
          continue;
        for (int i = 0; i < chunk.getNelems(); i++) { // 16 byte "heap ids"
          sa[count++] = headerParser.readHeapString(chunk.getSrcPos() + layout.getElemSize() * i);
        }
      }
      return sa;
    }

    if (dataType == DataType.OPAQUE) {
      Array opArray = Array.factory(DataType.OPAQUE, shape);
      assert (new Section(shape).computeSize() == layout.getTotalNelems());

      int count = 0;
      while (layout.hasNext()) {
        Layout.Chunk chunk = layout.next();
        if (chunk == null)
          continue;
        int recsize = layout.getElemSize();
        for (int i = 0; i < chunk.getNelems(); i++) {
          byte[] pa = new byte[recsize];
          raf.seek(chunk.getSrcPos() + i * recsize);
          raf.readFully(pa, 0, recsize);
          opArray.setObject(count++, ByteBuffer.wrap(pa));
        }
      }
      return opArray;
    }

    // normal case
    return IospHelper.readDataFill(raf, layout, dataType, fillValue, endian, convertChar);
  }

  // old way
  private StructureData readStructure(Structure s, ArrayStructureW asw, long dataPos)
      throws IOException, InvalidRangeException {
    StructureDataW sdata = new StructureDataW(asw.getStructureMembers());
    if (debug)
      System.out.println(" readStructure " + s.getFullName() + " dataPos = " + dataPos);

    for (Variable v2 : s.getVariables()) {
      H5header.Vinfo vinfo = (H5header.Vinfo) v2.getSPobject();
      if (debug)
        System.out.println(" readStructureMember " + v2.getFullName() + " vinfo = " + vinfo);
      Array dataArray = readData(v2, dataPos + vinfo.dataPos, v2.getShapeAsSection());
      sdata.setMemberData(v2.getShortName(), dataArray);
    }

    return sdata;
  }

  //////////////////////////////////////////////////////////////////////////
  // override base class

  @Override
  public void close() throws IOException {
    super.close();
    headerParser.close();
  }

  @Override
  public void reacquire() throws IOException {
    super.reacquire();
    headerParser.raf = this.raf;
  }

  @Override
  public String toStringDebug(Object o) {
    if (o instanceof Variable) {
      Variable v = (Variable) o;
      H5header.Vinfo vinfo = (H5header.Vinfo) v.getSPobject();
      return vinfo.toString();
    }
    return null;
  }

  @Override
  public String getDetailInfo() {
    Formatter f = new Formatter();
    ByteArrayOutputStream os = new ByteArrayOutputStream(100 * 1000);
    PrintWriter pw = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

    try {
      NetcdfFile ncfile = new NetcdfFileSubclass();
      H5header detailParser = new H5header(raf, ncfile, this);
      detailParser.read(pw);
      f.format("%s", super.getDetailInfo());
      f.format("%s", os.toString(CDM.UTF8));

    } catch (IOException e) {
      e.printStackTrace();
    }

    return f.toString();
  }

  @Override
  public Object sendIospMessage(Object message) {
    if (message.toString().equals(IOSP_MESSAGE_INCLUDE_ORIGINAL_ATTRIBUTES)) {
      includeOriginalAttributes = true;
      return null;
    }

    if (message.toString().equals("header"))
      return headerParser;

    if (message.toString().equals("headerEmpty")) {
      NetcdfFile ncfile = new NetcdfFileSubclass();
      return new H5header(raf, ncfile, this);
    }

    if (message.equals(IOSP_MESSAGE_GET_NETCDF_FILE_FORMAT)) {
      if (!headerParser.isNetcdf4()) {
        return null;
      }
      return headerParser.isClassic() ? NetcdfFileFormat.NETCDF4_CLASSIC : NetcdfFileFormat.NETCDF4;
    }

    return super.sendIospMessage(message);
  }

  // debug
  NetcdfFile getNetcdfFile() {
    return headerParser.ncfile;
  }

}
