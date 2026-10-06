// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;
public class Reexpress {
  static double[] clip(float[] m, double qx, double qy, double qz) {
    return new double[]{m[0]*qx+m[4]*qy+m[8]*qz+m[12], m[1]*qx+m[5]*qy+m[9]*qz+m[13], m[2]*qx+m[6]*qy+m[10]*qz+m[14]};
  }
  public static void main(String[] a) {
    double[] l = {0.3, 0.9, 0.1}; double n = Math.sqrt(0.91); for (int i=0;i<3;i++) l[i]/= Math.sqrt(0.3*0.3+0.81+0.01);
    for (double base : new double[]{0, 30000, 2.9e7}) {
      ShadowCascade c = new ShadowCascade();
      double ax = base + 12.3, ay = 80.7, az = -base - 4.9;
      c.fit(l, ax, ay, az, 128, 2048, 16, 16, 192);
      double bx = ax + 13.37, by = ay - 2.5, bz = az + 7.77;
      float[] ma = new float[16], mb = new float[16];
      c.matrix(ax, ay, az, ma, 0); c.matrix(bx, by, bz, mb, 0);
      double px = ax + 40.25, py = 64, pz = az - 20.5;
      double[] ca = clip(ma, px - ax, py - ay, pz - az), cb = clip(mb, px - bx, py - by, pz - bz);
      System.out.printf("base %.0f: clipA %.6f %.6f %.6f  clipB %.6f %.6f %.6f  (texel delta x %.4f)%n", base, ca[0], ca[1], ca[2], cb[0], cb[1], cb[2], (ca[0]-cb[0])*1024);
    }
  }
}
