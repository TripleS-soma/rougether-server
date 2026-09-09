import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
import statistics

base=Path(__file__).resolve().parent
rows=json.loads((base/'2026-09-10-comparison.json').read_text())['runs']
font=Path('/System/Library/Fonts/AppleSDGothicNeo.ttc')
if font.exists():
    plt.rcParams['font.family']=FontProperties(fname=str(font)).get_name()
plt.rcParams.update({'font.size':11, 'axes.unicode_minus':False, 'svg.fonttype':'path', 'axes.spines.top':False, 'axes.spines.right':False, 'axes.spines.left':False, 'axes.spines.bottom':False})
conditions=[('2',10),('2',60),('4',60)]
groups=[[row for row in rows if (row['condition']['limits']['SCALE_API_CPUS'],row['condition']['warmup_seconds'])==condition] for condition in conditions]
assert all(len(group)==3 for group in groups), 'Need exactly 3 repeats per condition'
labels=['API 2 CPU\n워밍업 10초','API 2 CPU\n워밍업 60초','API 4 CPU\n워밍업 60초']
colors=['#a3473e','#b17725','#246e87']
fig,axes=plt.subplots(1,2,figsize=(11.2,5.1),facecolor='#fafaf7')
fig.subplots_adjust(left=.08,right=.97,top=.74,bottom=.23,wspace=.30)
fig.text(.06,.92,'1,000 RPS에서 워밍업과 CPU 할당 비교',fontsize=21,weight='bold',color='#1d292d')
fig.text(.06,.855,'동일한 혼합 요청 · 조건마다 30초씩 3회 측정',fontsize=12,color='#556067')
for ax in axes:
    ax.set_facecolor('#fafaf7')
    ax.set_xticks(range(3),labels)
    ax.tick_params(axis='both',length=0,pad=9)
    ax.grid(axis='y',color='#dce0dd',linewidth=.7)
    ax.set_axisbelow(True)
    ax.set_xlim(-.55,2.55)
for panel,metric,title in [(0,'latency','가장 느린 API의 p95 (ms)'),(1,'drop','시작하지 못한 요청 (건)')]:
    ax=axes[panel]
    all_values=[]
    for i,group in enumerate(groups):
        values=[max(v['p(95)'] for v in row['endpoint_response_latency_ms'].values()) if metric=='latency' else row['dropped'] for row in group]
        all_values.extend(values)
        ax.plot([i,i],[min(values),max(values)],color=colors[i],linewidth=2,zorder=3)
        ax.scatter([i-.10,i,i+.10],values,color=colors[i],s=42,zorder=4)
        median=statistics.median(values)
        ax.plot([i-.22,i+.22],[median,median],color=colors[i],linewidth=3,zorder=4)
        ax.annotate(f'{min(values):,.0f}–{max(values):,.0f}' if max(values)!=min(values) else f'{median:,.0f}',(i,max(values)),xytext=(0,13),textcoords='offset points',ha='center',color=colors[i],weight='bold')
    ax.set_title(title,loc='left',fontsize=13,pad=17)
    ax.set_ylim(-max(all_values+[1])*.04,max(all_values+[300 if metric=='latency' else 1])*1.23)
    if metric=='latency':
        ax.axhline(300,color='#7a8585',linestyle=(0,(4,3)),linewidth=1)
        ax.text(2.5,310,'기준 300ms',ha='right',fontsize=10,color='#637071')
fig.text(.06,.105,'점: 각 회차  |  굵은 선: 중앙값  |  마지막 조건은 API CPU를 2배 할당',fontsize=10,color='#556067')
fig.text(.06,.055,'합성 사용자 1,000명 · 읽기 80% / 완료 20% · MySQL 2 CPU / 2GiB · 운영·장시간 처리량 미검증',fontsize=10,color='#556067')
for suffix in ('png','svg'):
    path=base/f'2026-09-10-comparison.{suffix}'
    fig.savefig(path,dpi=170,facecolor=fig.get_facecolor())
    if suffix=='svg':
        path.write_text('\n'.join(line.rstrip() for line in path.read_text().splitlines())+'\n')
print(base/'2026-09-10-comparison.png')
