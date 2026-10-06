// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
public class GenMatrices {
  public static void main(String[] a) throws Exception {
    double camX = 1000.3, camY = 70.6, camZ = -2000.2;
    double ang = Math.toRadians(Double.parseDouble(a[1])); // sun toward +X
    double[] l = {-Math.sin(ang), Math.cos(ang), 0};
    ShadowCascade near = new ShadowCascade(), far = new ShadowCascade();
    int res = 2048;
    near.fit(l, camX, camY, camZ, 32, res, 16, 16, 192);
    far.fit(l, camX, camY, camZ, 128, res, 16, 16, 192);
    float[] m = new float[32];
    near.matrix(camX, camY, camZ, m, 0);
    far.matrix(camX, camY, camZ, m, 16);
    ByteBuffer b = ByteBuffer.allocate(4 * (32 + 3 + 4 + 3)).order(ByteOrder.LITTLE_ENDIAN);
    for (float f : m) b.putFloat(f);
    b.putFloat((float) l[0]).putFloat((float) l[1]).putFloat((float) l[2]);
    b.putFloat((float) near.texelWorld()).putFloat((float) far.texelWorld());
    b.putFloat((float) (near.texelWorld() / near.depthRange())).putFloat((float) (far.texelWorld() / far.depthRange()));
    b.putFloat((float) camX).putFloat((float) camY).putFloat((float) camZ);
    Files.write(Paths.get(a[0]), b.array());
    // sanity in double: world point under occluder
    System.out.println("near depthRange " + near.depthRange() + " texel " + near.texelWorld());
    // stability: moving the camera by sub-texel amounts must not move a static world point's texel
    double px = 1003.5, py = 64, pz = -2008;
    for (double d : new double[]{0, 0.01, 0.37, 1.9}) {
      ShadowCascade c = new ShadowCascade();
      c.fit(l, camX + d, camY, camZ + d * 0.5, 128, res, 16, 16, 192);
      float[] mm = new float[16];
      c.matrix(camX + d, camY, camZ + d*0.5, mm, 0);
      double qx = px - (camX + d), qy = py - camY, qz = pz - (camZ + d*0.5);
      double cx = mm[0]*qx + mm[4]*qy + mm[8]*qz + mm[12];
      double cy = mm[1]*qx + mm[5]*qy + mm[9]*qz + mm[13];
      System.out.printf("shift %.2f: texel x %.4f y %.4f%n", d, (cx*0.5+0.5)*res, (0.5-cy*0.5)*res);
    }
  }
}
