/*
 * Copyright (c) 1998-2018 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package thredds.inventory;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import ucar.unidata.util.StringUtil2;
import javax.annotation.concurrent.ThreadSafe;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Formatter;

/**
 * Parses the collection specification string.
 * <p>
 * the idea is that one copies the full path of an example dataset, then edits it
 * </p>
 * <p>
 * Example: "/data/ldm/pub/native/grid/NCEP/GFS/Alaska_191km/** /GFS_Alaska_191km_#yyyyMMdd_HHmm#\.grib1$"
 * </p>
 * <ul>
 * <li>rootDir ="/data/ldm/pub/native/grid/NCEP/GFS/Alaska_191km"/</li>
 * <li>subdirs=true (because ** is present)</li>
 * <li>dateFormatMark="GFS_Alaska_191km_#yyyyMMdd_HHmm"</li>
 * <li>regExp='GFS_Alaska_191km_.............\.grib1$</li>
 * </ul>
 * <p>
 * Example: "Q:/grid/grib/grib1/data/agg/.*\.grb"
 * </p>
 * <ul>
 * <li>rootDir ="Q:/grid/grib/grib1/data/agg/"/</li>
 * <li>subdirs=false</li>
 * <li>dateFormatMark=null</li>
 * <li>useName=yes</li>
 * <li>regexp= ".*\.grb" (anything ending with .grb)</li>
 * </ul>
 *
 * @see "https://www.unidata.ucar.edu/projects/THREDDS/tech/tds4.2/reference/collections/CollectionSpecification.html"
 * @author caron
 * @since Jul 7, 2009
 */
@ThreadSafe
public class CollectionSpecParser {
  private final String spec;
  private final String rootDir;
  private final boolean subdirs; // recurse into subdirectories under the root dir
  private final boolean filterOnName; // filter on name, else on entire path
  private final Pattern filter; // regexp filter
  private final String dateFormatMark;

  /**
   * Single spec : "/topdir/** /#dateFormatMark#regExp"
   * This only allows the dateFormatMark to be in the file name, not anywhere else in the filename path,
   * and you cant use any part of the dateFormat to filter on.
   * 
   * @param collectionSpec the collection Spec
   * @param errlog put error messages here, may be null
   */
  public CollectionSpecParser(String collectionSpec, Formatter errlog) {
    this.spec = collectionSpec.trim();
    int posFilter;

    int posGlob = collectionSpec.indexOf("/**/");
    if (posGlob > 0) {
      rootDir = collectionSpec.substring(0, posGlob);
      posFilter = posGlob + 3;
      subdirs = true;

    } else {
      subdirs = false;
      posFilter = collectionSpec.lastIndexOf('/');
      if (posFilter > 0)
        rootDir = collectionSpec.substring(0, posFilter);
      else
        rootDir = System.getProperty("user.dir"); // working directory
    }

    File locFile = new File(rootDir);
    if (!locFile.exists() && errlog != null) {
      errlog.format(" Directory %s does not exist %n", rootDir);
    }

    // optional filter
    String filter = null;
    if (posFilter < collectionSpec.length() - 2)
      filter = collectionSpec.substring(posFilter + 1); // remove topDir

    if (filter != null) {
      // optional dateFormatMark
      int posFormat = filter.indexOf('#');
      if (posFormat >= 0) {
        // check for two hash marks
        int posFormat2 = filter.lastIndexOf('#');

        if (posFormat != posFormat2) { // two hash
          dateFormatMark = filter.substring(0, posFormat2); // everything up to the second hash
          filter = StringUtil2.remove(filter, '#'); // remove hashes, replace with .
          StringBuilder sb = new StringBuilder(filter);
          for (int i = posFormat; i < posFormat2 - 1; i++)
            sb.setCharAt(i, '.');
          String regExp = sb.toString();
          this.filter = Pattern.compile(regExp);

        } else { // one hash
          dateFormatMark = filter; // everything
          String regExp = filter.substring(0, posFormat) + "*";
          this.filter = Pattern.compile(regExp);
        }

      } else { // no hash (dateFormatMark)
        dateFormatMark = null;
        this.filter = Pattern.compile(filter);
      }
    } else {
      dateFormatMark = null;
      this.filter = null;
    }

    this.filterOnName = true;
  }

  public CollectionSpecParser(String rootDir, String regExp, Formatter errlog) {
    this.rootDir = StringUtil2.removeFromEnd(rootDir, '/');
    this.subdirs = true;
    this.spec = this.rootDir + "/" + regExp;
    this.filter = Pattern.compile(spec);
    this.dateFormatMark = null;
    this.filterOnName = false;
  }

  public PathMatcher getPathMatcher() {
    if (spec.startsWith("regex:") || spec.startsWith("glob:")) { // experimental
      return FileSystems.getDefault().getPathMatcher(spec);
    } else {
      return new BySpecp();
    }
  }

  private class BySpecp implements java.nio.file.PathMatcher {
    @Override
    public boolean matches(Path path) {
      Matcher matcher = filter.matcher(path.getFileName().toString());
      return matcher.matches();
    }
  }

  public String getRootDir() {
    return rootDir;
  }

  public boolean wantSubdirs() {
    return subdirs;
  }

  public Pattern getFilter() {
    return filter;
  }

  public boolean getFilterOnName() {
    return filterOnName;
  }

  public String getDateFormatMark() {
    return dateFormatMark;
  }

  @Override
  public String toString() {
    return "CollectionSpecParser{" + "\n   topDir='" + rootDir + '\'' + "\n   subdirs=" + subdirs + "\n   regExp='"
        + filter + '\'' + "\n   dateFormatMark='" + dateFormatMark + '\'' +
        // "\n useName=" + useName +
        "\n}";
  }
}
