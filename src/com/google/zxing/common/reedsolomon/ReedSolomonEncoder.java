/*
 * Copyright 2008 ZXing authors
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

package com.google.zxing.common.reedsolomon;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Implements Reed-Solomon enbcoding, as the name implies.</p>
 *
 * @author Sean Owen
 * @author William Rucklidge
 */
public final class ReedSolomonEncoder {

  private final GenericGF field;
  private final List<GenericGFPoly> cachedGenerators;

  public ReedSolomonEncoder(GenericGF field) {
    this.field = field;
    this.cachedGenerators = new ArrayList<>();
    cachedGenerators.add(new GenericGFPoly(field, new int[]{1}));
  }

  private GenericGFPoly buildGenerator(int degree) {
    if (degree >= cachedGenerators.size()) {
      GenericGFPoly lastGenerator = cachedGenerators.get(cachedGenerators.size() - 1);
      for (int d = cachedGenerators.size(); d <= degree; d++) {
        GenericGFPoly nextGenerator = lastGenerator.multiply(
            new GenericGFPoly(field, new int[] { 1, field.exp(d - 1 + field.getGeneratorBase()) }));
        cachedGenerators.add(nextGenerator);
        lastGenerator = nextGenerator;
      }
    }
    return cachedGenerators.get(degree);
  }

  public void encode(int[] toEncode, int ecBytes) {
    if (ecBytes == 0) {
      throw new IllegalArgumentException("No error correction bytes");
    }
    int dataBytes = toEncode.length - ecBytes;
    if (dataBytes <= 0) {
      throw new IllegalArgumentException("No data bytes provided");
    }
    GenericGFPoly generator = buildGenerator(ecBytes);
    int[] infoCoefficients = new int[dataBytes];
    System.arraycopy(toEncode, 0, infoCoefficients, 0, dataBytes);
    GenericGFPoly info = new GenericGFPoly(field, infoCoefficients);
    info = info.multiplyByMonomial(ecBytes, 1);
    GenericGFPoly remainder = info.divide(generator)[1];
    int[] coefficients = remainder.getCoefficients();
    int numZeroCoefficients = ecBytes - coefficients.length;
    for (int i = 0; i < numZeroCoefficients; i++) {
      toEncode[dataBytes + i] = 0;
    }
    System.arraycopy(coefficients, 0, toEncode, dataBytes + numZeroCoefficients, coefficients.length);
  }

  public void encodeNoneSym(int[] toEncode, boolean[] flag, int k){
    int n = toEncode.length;
    int[][] a = new int[n - k][n - k + 1];
    getMatrix(a, flag, n, k);

    int[] Y = new int[n - k];
    getY(Y, toEncode, flag, n, k);

    // 增广矩阵
    for(int i = 0; i < n - k; i++)
      a[i][n - k] = Y[i];

    gauss_row_xiaoqu(a, n - k);
    gauss_calculate(a, n - k);

    int count = 0;
    for(int i = 0; i < n; i++){
      if(flag[i] == false)
        toEncode[i] = a[count++][n - k];
    }
  }

  private void getMatrix(int[][] a, boolean[] flag, int n, int k){
    int[] ci = new int[n - k];
    int count = 0;
    //c0...cn中未被指定的n - k个c
    for(int i = 0; i < n; i++){
      if(flag[i] == false)
        ci[count++] = i;
    }

    for(int i = 0; i < n - k; i++){
      for(int j = 0; j < n - k; j++){
        a[i][j] = field.expTable[(i * (n - 1 - ci[j])) % 255];
      }
    }
  }

  private void getY(int[] Y, int[] code, boolean[] flag, int n, int k){
    int[] ci = new int[k];
    int count = 0;
    for(int i = 0; i < n; i++){
      if(flag[i] == true)
        ci[count++] = i;
    }

    for(int i = 0; i < n - k; i++){
      Y[i] = 0;
      for(int j = 0; j < k; j++){
        if(code[ci[j]] != 0){
          int exp = (field.logTable[code[ci[j]]] + i * (n - 1 - ci[j])) % 255;
          Y[i] ^= field.expTable[exp];
        }
      }
    }
  }

  private void exchange_hang(int[][] a, int r1, int r2){
    int[] tmp = a[r1];
    a[r1] = a[r2];
    a[r2] = tmp;
  }

  private void gauss_row_xiaoqu(int[][] a, int row){
    int k, i, j ,maxi;
    for(k = 0; k < row - 1; k++){
      j = k;
      for(maxi = i = k; i < row; i++){
        if(a[i][j] > a[maxi][j])
          maxi = i;
      }
      if(a[maxi][k] == 0)
        continue;
      if(maxi != k)
        exchange_hang(a, k, maxi);

      //a[k][k] != 0
      for(i = k + 1; i < row; i++){
        if(a[i][k] == 0)
          continue;
        //a[i][k] != 0
        int dltExp = (255 + field.logTable[a[i][k]] - field.logTable[a[k][k]]) % 255;
        //col + 1 -> 增广
        for(j = k; j < row + 1; j++){
          if(a[k][j] != 0){
            a[i][j] ^= field.expTable[(field.logTable[a[k][j]] + dltExp) % 255];
          }
        }
      }
    }
  }

  private void gauss_calculate(int[][] a, int row){
    if(a[row - 1][row] != 0)
      a[row - 1][row] = field.expTable[(255 + field.logTable[a[row - 1][row]] - field.logTable[a[row - 1][row - 1]]) % 255];

    for(int i = row -2; i >= 0; i--){
      int sum_ax = 0;
      for(int j = i + 1; j < row; j++){
        if(a[i][j] != 0 && a[j][row] != 0){
          sum_ax ^= field.expTable[(field.logTable[a[i][j]] + field.logTable[a[j][row]]) % 255];
        }
      }
      a[i][row] ^= sum_ax;
      if(a[i][row] != 0) {
        a[i][row] = field.expTable[(255 + field.logTable[a[i][row]] - field.logTable[a[i][i]]) % 255];
      }
    }
  }
}
