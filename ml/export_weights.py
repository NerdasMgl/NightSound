"""把 YAMNet 的 Keras 权重折叠 BatchNorm 后导出为 float16 二进制，供手机端纯 Java 前向使用。"""
import sys, struct, numpy as np
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import yamnet_np as Y

# 用法：python export_weights.py <输出 yamnet.bin> <官方 yamnet.h5 路径>
OUT = sys.argv[1]
H5 = sys.argv[2]
net = Y.YamNet(H5)
chunks = []

def put(a):
    a = np.ascontiguousarray(a, dtype=np.float32)
    chunks.append(a.astype('<f2').tobytes())

# layer1: k (3,3,1,32), bn(scale, shift)
_, k, bn, _ = net.layers[0]
put((k[:, :, 0, :] * bn[0][None, None, :]))          # [3][3][32]
put(bn[1])
for L in net.layers[1:]:
    _, dk, dbn, pk, pbn, s = L
    put(dk[:, :, :, 0] * dbn[0][None, None, :])      # [3][3][C]
    put(dbn[1])
    put(pk[0, 0] * pbn[0][None, :])                  # [Cin][Cout]
    put(pbn[1])
put(net.W)                                           # [1024][521]
put(net.b)
data = b'YMN1' + b''.join(chunks)
open(OUT, 'wb').write(data)
print(len(data), 'bytes')
