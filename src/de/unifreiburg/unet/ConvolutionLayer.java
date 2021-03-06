/**************************************************************************
 *
 * Copyright (C) 2018 Thorsten Falk
 *
 *        Image Analysis Lab, University of Freiburg, Germany
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 **************************************************************************/

package de.unifreiburg.unet;

import caffe.Caffe;

/**
 * ConvolutionLayer provides functionality to compute the required
 * memory of the corresponding caffe ConvolutionLayer.
 *
 * @author Thorsten Falk
 * @version 1.0
 * @since 1.0
 */
public class ConvolutionLayer extends NetworkLayer {

  /**
   * Create a new <code>ConvolutionLayer</code> object.
   *
   * @param layerParam the parameters used to setup the layer in compiled
   *   protocol buffer format
   * @param net the parent <code>Net</code> object
   * @param in the input blobs for this layer
   *
   * @throws BlobException if the convolution would reduce any input blob to
   *   zero size or any input blob extent is no multiple of the stride
   *
   * @see caffe.Caffe.ConvolutionParameter
   */
  public ConvolutionLayer(
      Caffe.LayerParameter layerParam, Net net, CaffeBlob[] in)
      throws BlobException {
    super(layerParam, net, in);
    Caffe.ConvolutionParameter cp = layerParam.getConvolutionParam();
    _kernelShape = new int[in[0].shape().length - 2];
    for (int d = 0; d < cp.getKernelSizeCount(); ++d)
        _kernelShape[d] = cp.getKernelSize(d);
    for (int d = cp.getKernelSizeCount(); d < _kernelShape.length; ++d)
        _kernelShape[d] = _kernelShape[d - 1];
    _pad = new int[in[0].shape().length - 2];
    if (cp.getPadCount() > 0) {
      for (int d = 0; d < cp.getPadCount(); ++d)
          _pad[d] = cp.getPad(d);
      for (int d = cp.getPadCount(); d < _pad.length; ++d)
          _pad[d] = _pad[d - 1];
    }
    else for (int d = 0; d < _pad.length; ++d) _pad[d] = 0;
    _stride = new int[in[0].shape().length - 2];
    if (cp.getStrideCount() > 0) {
      for (int d = 0; d < cp.getStrideCount(); ++d)
          _stride[d] = cp.getStride(d);
      for (int d = cp.getStrideCount(); d < _stride.length; ++d)
          _stride[d] = _stride[d - 1];
    }
    else for (int d = 0; d < _stride.length; ++d) _stride[d] = 1;
    _dilation = new int[in[0].shape().length - 2];
    if (cp.getDilationCount() > 0) {
      for (int d = 0; d < cp.getDilationCount(); ++d)
          _dilation[d] = cp.getDilation(d);
      for (int d = cp.getDilationCount(); d < _dilation.length; ++d)
          _dilation[d] = _dilation[d - 1];
    }
    else for (int d = 0; d < _dilation.length; ++d) _dilation[d] = 1;

    for (int i = 0; i < layerParam.getTopCount(); ++i)
    {
      long[] outputShape = new long[in[i].shape().length];
      outputShape[0] = in[i].nSamples();
      outputShape[1] = cp.getNumOutput();
      for (int d = 0; d < _kernelShape.length; ++d) {
        long numerator = in[i].shape()[d + 2] + 2 * _pad[d] -
            (_dilation[d] * (_kernelShape[d] - 1) + 1);
        if (numerator <= 0) throw new BlobException(
            "Convolution would reduce output blob size to zero");
        if (numerator % _stride[d] != 0) throw new BlobException(
            "Invalid stride for convolution");
        outputShape[d + 2] = numerator / _stride[d] + 1;
      }
      _out[i] = new CaffeBlob(
          layerParam.getTop(i), outputShape, this, true, true);
    }
    for (CaffeBlob blob : in) blob.setOnGPU(true);
  }

  /**
   * {@inheritDoc}
   *
   * @return a string representation of kernel shape, padding, stride and
   *   dilation
   */
  @Override
  public String paramString() {
    String res = "kernelShape: [ ";
    for (int extent : _kernelShape) res += extent + " ";
    res += "]";
    res += " pad: [ ";
    for (int extent : _pad) res += extent + " ";
    res += "]";
    res += " stride: [ ";
    for (int extent : _stride) res += extent + " ";
    res += "]";
    res += " dilation: [ ";
    for (int extent : _dilation) res += extent + " ";
    res += "]";
    return res;
  }

  /**
   * {@inheritDoc}
   * <p>
   * #parameters = (#input channels * #kernel entries + 1) * #output channels
   *
   * @return {@inheritDoc}
   */
  @Override
  public long memoryParameters() {
    long kernelSize = 1;
    for (int extent: _kernelShape) kernelSize *= extent;
    return 4 * _out[0].nChannels() * (inputBlobs()[0].nChannels() *
                                      kernelSize + 1);
  }

  /**
   * {@inheritDoc}
   * <p>
   * With cuDNN: constant 24MB (upper bound of allocated workspace)
   * <p>
   * Without cuDNN: 0 MB if this is a 1x1 convolution, otherwise an im2col
   * is allocated with size #outputPixels * #input channels * #kernel entries
   *
   * @param cuDNN returns required memory for workspaces if <code>true</code>,
   *   or memory required for alternative data structures otherwise
   * @return {@inheritDoc}
   */
  @Override
  public long memoryOverhead(boolean cuDNN) {
    if (cuDNN) return 3 * 8 * 1024 * 1024;
    long kernelSize = 1;
    for (int extent: _kernelShape) kernelSize *= extent;
    return (kernelSize > 0) ?
        4 * _out[0].count(2) * inputBlobs()[0].nChannels() * kernelSize : 0;
  }

  private final int[] _kernelShape;
  private final int[] _pad;
  private final int[] _stride;
  private final int[] _dilation;

}
