# Converts taatest's raw RGBA dumps (TAATEST_DUMP=dir) to PNG; rows are GL order (row 0 = bottom), so flip.
import sys, glob, os, re
from PIL import Image
for f in glob.glob(os.path.join(sys.argv[1], "*.rgba")):
    w, h = map(int, re.search(r"_(\d+)x(\d+)\.rgba$", f).groups())
    Image.frombytes("RGBA", (w, h), open(f, "rb").read()).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB").save(f[:-5] + ".png")
