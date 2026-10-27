"""YAMNet 的 numpy 参考实现：特征提取 + MobileNetV1 前向，用来和 TFLite 对齐，并导出手机端用的权重。"""
import numpy as np, h5py

SR = 16000
WIN, HOP, NFFT = 400, 160, 512
MEL_BANDS, MEL_MIN, MEL_MAX, LOG_OFFSET = 64, 125.0, 7500.0, 0.001
PATCH_FRAMES = 96


def hz2mel(f):
    return 1127.0 * np.log(1.0 + np.asarray(f, dtype=np.float64) / 700.0)


def mel_matrix():
    n_spec = NFFT // 2 + 1
    lin = np.linspace(0.0, SR / 2.0, n_spec)[1:]
    spec_mel = hz2mel(lin)[:, None]
    edges = np.linspace(hz2mel(MEL_MIN), hz2mel(MEL_MAX), MEL_BANDS + 2)
    lo, ce, up = edges[:-2][None, :], edges[1:-1][None, :], edges[2:][None, :]
    lower = (spec_mel - lo) / (ce - lo)
    upper = (up - spec_mel) / (up - ce)
    w = np.maximum(0.0, np.minimum(lower, upper))
    return np.vstack([np.zeros((1, MEL_BANDS)), w]).astype(np.float32)  # (257, 64)


_MEL = mel_matrix()
_HANN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(WIN) / WIN)).astype(np.float32)  # 周期 Hann


def log_mel(wave):
    """wave: float32 (n,) 采样率 16k，返回 (frames, 64)。"""
    n = len(wave)
    frames = 1 + (n - WIN) // HOP
    idx = np.arange(WIN)[None, :] + HOP * np.arange(frames)[:, None]
    x = wave[idx] * _HANN[None, :]
    spec = np.abs(np.fft.rfft(x, n=NFFT, axis=1)).astype(np.float32)
    mel = spec @ _MEL
    return np.log(mel + LOG_OFFSET).astype(np.float32)


class YamNet:
    def __init__(self, h5path):
        f = h5py.File(h5path, 'r')

        def g(path):
            return np.array(f[path])

        self.layers = []
        # layer1: 常规卷积
        k = g('layer1/conv/layer1/conv/kernel:0')  # (3,3,1,32)
        bn = self._bn(f, 'layer1/conv/bn/layer1/conv/bn/')
        self.layers.append(('conv', k, bn, 2))
        cfg = [(1, 64), (2, 128), (1, 128), (2, 256), (1, 256), (2, 512), (1, 512), (1, 512), (1, 512), (1, 512), (1, 512), (2, 1024), (1, 1024)]
        for i, (stride, filt) in enumerate(cfg, start=2):
            dk = g('layer%d/depthwise_conv/layer%d/depthwise_conv/depthwise_kernel:0' % (i, i))  # (3,3,C,1)
            dbn = self._bn(f, 'layer%d/depthwise_conv/bn/layer%d/depthwise_conv/bn/' % (i, i))
            pk = g('layer%d/pointwise_conv/layer%d/pointwise_conv/kernel:0' % (i, i))  # (1,1,Cin,Cout)
            pbn = self._bn(f, 'layer%d/pointwise_conv/bn/layer%d/pointwise_conv/bn/' % (i, i))
            self.layers.append(('sep', dk, dbn, pk, pbn, stride))
        self.W = g('logits/logits/kernel:0')
        self.b = g('logits/logits/bias:0')

    @staticmethod
    def _bn(f, p):
        beta = np.array(f[p + 'beta:0']); mean = np.array(f[p + 'moving_mean:0']); var = np.array(f[p + 'moving_variance:0'])
        scale = 1.0 / np.sqrt(var + 1e-4)          # scale=False -> gamma=1
        return scale.astype(np.float32), (beta - mean * scale).astype(np.float32)   # y = x*scale + shift

    @staticmethod
    def _pad_same(x, k, s):
        h, w = x.shape[:2]
        oh, ow = -(-h // s), -(-w // s)
        ph = max((oh - 1) * s + k - h, 0); pw = max((ow - 1) * s + k - w, 0)
        top, left = ph // 2, pw // 2
        return np.pad(x, ((top, ph - top), (left, pw - left), (0, 0))), oh, ow

    def _conv3(self, x, k, s):
        xp, oh, ow = self._pad_same(x, 3, s)
        cin, cout = k.shape[2], k.shape[3]
        out = np.zeros((oh, ow, cout), np.float32)
        for dy in range(3):
            for dx in range(3):
                patch = xp[dy:dy + s * oh:s, dx:dx + s * ow:s, :]
                out += patch @ k[dy, dx]
        return out

    def _dw(self, x, k, s):
        xp, oh, ow = self._pad_same(x, 3, s)
        out = np.zeros((oh, ow, x.shape[2]), np.float32)
        for dy in range(3):
            for dx in range(3):
                out += xp[dy:dy + s * oh:s, dx:dx + s * ow:s, :] * k[dy, dx, :, 0]
        return out

    def forward(self, feats):
        """feats: (96,64) -> (embedding1024, logits521, scores521)"""
        x = feats[:, :, None].astype(np.float32)
        kind, k, bn, s = self.layers[0]
        x = np.maximum(self._conv3(x, k, s) * bn[0] + bn[1], 0)
        for L in self.layers[1:]:
            _, dk, dbn, pk, pbn, s = L
            x = np.maximum(self._dw(x, dk, s) * dbn[0] + dbn[1], 0)
            h, w, c = x.shape
            x = np.maximum((x.reshape(-1, c) @ pk[0, 0]).reshape(h, w, -1) * pbn[0] + pbn[1], 0)
        emb = x.mean(axis=(0, 1))
        logits = emb @ self.W + self.b
        return emb, logits, 1.0 / (1.0 + np.exp(-logits))
