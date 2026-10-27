import sys, glob, os, numpy as np
import os
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from ai_edge_litert.interpreter import Interpreter
M=os.environ.get('YEHANG_DATA','data')+'/'   # 该目录下需要 models/yamnet_repo/yamnet.tflite 和 esc/raw/<类名>/*.pcm
it=Interpreter(model_path=M+'models/yamnet_repo/yamnet.tflite'); it.allocate_tensors()
inp=it.get_input_details()[0]['index']; outs=it.get_output_details()
def run(seg):
    it.set_tensor(inp, seg[None,:].astype(np.float32)); it.invoke()
    return it.get_tensor(outs[0]['index'])[0].copy()
classes=sorted(os.listdir(M+'esc/raw'))
data={}
for c in classes:
    data[c]=[np.frombuffer(open(p,'rb').read(),dtype='<i2').astype(np.float32)/32768 for p in sorted(glob.glob(M+'esc/raw/%s/*.pcm'%c))]
def windows(w, gain_db=0.0, norm=False):
    g=10**(gain_db/20)
    out=[]
    for s in range(0,len(w)-15600+1,7800):
        seg=w[s:s+15600]*g
        if norm:
            rms=np.sqrt(np.mean(seg**2))+1e-9
            seg=seg*min(30.0, 0.05/rms)
        out.append(seg)
    return out
def auc(pos,neg):
    pos=np.array(pos); neg=np.array(neg)
    return np.mean([(p>neg).mean()+0.5*(p==neg).mean() for p in pos])
IDX={'snore':38,'cough':42,'throat':43,'sneeze':44,'breath':36,'speech':0,'chew':49,'biting':50,'crunch':493,'scrape':469,'scratch':468,'rub':470,'squeak':355,'creak':480,'toothbrush':369,'snort':41,'gasp':39}
if __name__=='__main__':
    for gain,norm in [(0,False),(-20,False),(-20,True),(-35,True)]:
        res={}
        for c in classes:
            res[c]=[]
            for w in data[c]:
                sc=np.array([run(x) for x in windows(w,gain,norm)])
                res[c].append(sc.max(axis=0))
        print('=== gain',gain,'norm',norm)
        sn=[r[38] for r in res['snoring']]
        others=[r[38] for c in classes if c!='snoring' for r in res[c]]
        print('snore score: pos mean %.3f median %.3f; neg mean %.3f; AUC %.3f'%(np.mean(sn),np.median(sn),np.mean(others),auc(sn,others)))
        for t in (0.1,0.2,0.3,0.5):
            tp=np.mean(np.array(sn)>=t); 
            fp={c:np.mean([r[38]>=t for r in res[c]]) for c in classes if c!='snoring'}
            worst=sorted(fp.items(),key=lambda kv:-kv[1])[:3]
            print(' thr %.1f recall %.2f overall FP %.3f worst %s'%(t,tp,np.mean([v for v in fp.values()]),[(k,round(v,2)) for k,v in worst]))
        cg=[r[42] for r in res['coughing']]
        print('cough recall@0.3 %.2f'%np.mean(np.array(cg)>=0.3))
        for k in ('toothbrush','scrape','chew','scratch'):
            print(' %s on brushing_teeth: mean %.2f; on others mean %.3f'%(k,np.mean([r[IDX[k]] for r in res['brushing_teeth']]),np.mean([r[IDX[k]] for c in classes if c!='brushing_teeth' for r in res[c]])))
