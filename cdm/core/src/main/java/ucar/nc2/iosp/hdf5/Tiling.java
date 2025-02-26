/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.iosp.hdf5;

import java.util.Formatter;

/**
 * A Tiling divides a multidimensional index into tiles.
 * Abstraction of HDF5 chunking.
 * <p>
 * Index are points in the original multidimensional index.
 * Tiles are points in the tiled space.
 * <p>
 * Each tile has the same size, given by tileSize.
 *
 * @author caron
 * @since Jul 20, 2007
 */
public class Tiling {

  private final int rank;
  private final int[] shape; // overall data shape - may be larger than actual variable shape
  private final int[] chunk; // actual storage is in this shape
  private final int[] stride; // for computing time index

  /**
   * Create a Tiling
   * 
   * @param shape overall shape of the dataset's index space
   * @param chunk tile size. may be larger than the shape.
   */
  public Tiling(int[] shape, int[] chunk) {
    assert shape.length <= chunk.length; // convenient to allow tileSize to have (an) extra dimension at the end
                                         // to accomodate hdf5 storage, which has the element size
    this.rank = shape.length;
    this.chunk = chunk;
    this.shape = new int[rank];
    for (int i = 0; i < rank; i++)
      this.shape[i] = Math.max(shape[i], chunk[i]);
    // int[] tile = tile(this.shape);

    int[] tileSize = new int[rank];
    for (int i = 0; i < rank; i++) {
      tileSize[i] = (this.shape[i] + chunk[i] - 1) / chunk[i];
    }

    this.stride = new int[rank];
    int strider = 1;
    for (int k = rank - 1; k >= 0; k--) {
      stride[k] = strider;
      strider *= tileSize[k];
    }
  }

  String show(int[] a) {
    Formatter f = new Formatter();
    for (int val : a)
      f.format("%3d,", val);
    return f.toString();
  }

  /**
   * Compute the tile
   * 
   * @param pt index point
   * @return corresponding tile
   */
  public int[] tile(int[] pt) {
    // assert pt.length == rank;
    int useRank = Math.min(rank, pt.length); // eg varlen (datatype 9) has mismatch
    int[] tile = new int[useRank];
    for (int i = 0; i < useRank; i++) {
      // 7/30/2016 jcaron. Apparently in some cases, at the end of the array, the index can be greater than the shape.
      // eg cdmUnitTest/formats/netcdf4/UpperDeschutes_t4p10_swemelt.nc
      // Presumably to have even chunks. Could try to calculate the last even chunk.
      // For now im removing this consistency check.
      // assert shape[i] >= pt[i] : String.format("shape[%s]=(%s) should not be less than pt[%s]=(%s)", i, shape[i], i,
      // pt[i]);
      tile[i] = pt[i] / chunk[i]; // seems wrong, rounding down ??
    }
    return tile;
  }

  /**
   * Get order based on which tile the pt belongs to
   * 
   * @param pt index point
   * @return order number based on which tile the pt belongs to
   */
  public int order(int[] pt) {
    int[] tile = tile(pt);
    int order = 0;
    int useRank = Math.min(rank, pt.length); // eg varlen (datatype 9) has mismatch
    for (int i = 0; i < useRank; i++)
      order += stride[i] * tile[i];
    return order;
  }

  /**
   * Create an ordering of index points based on which tile the point is in.
   * 
   * @param p1 index point 1
   * @param p2 index point 2
   * @return order(p1) - order(p2) : negative if p1 < p2, positive if p1 > p2 , 0 if equal
   */
  public int compare(int[] p1, int[] p2) {
    return order(p1) - order(p2);
  }
}
