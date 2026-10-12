/* Read-only Qualcomm NAS Get RF Band Information, QMI msg 0x0031.
 * The reported bands are ACTIVE RF states, NOT hardware capabilities, carrier permission, or complete enabled inventory.
 * QRTR service=3, node=0, port=82 observed on OnePlus PJD110.
 */
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif
struct sockaddr_qrtr {sa_family_t sq_family; uint32_t sq_node; uint32_t sq_port;};
static unsigned rd16(const uint8_t*p){return (unsigned)p[0]|((unsigned)p[1]<<8);}
static uint32_t rd32(const uint8_t*p){return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static const char *rat(unsigned r){switch(r){case 1:return "CDMA-1x";case 2:return "CDMA-1xEVDO";case 4:return "GSM";case 5:return "UMTS";case 8:return "LTE";case 12:return "NR5G";default:return "OTHER/UNKNOWN";}}
static void entries(const uint8_t*v,size_t len,unsigned type){
 if(!len){printf("TLV 0x%02X empty\n",type);return;}
 unsigned count=v[0];size_t width=type==0x11?7:5;
 printf("%s list count=%u, bytes=%zu\n",type==0x11?"Extended (32-bit channel)":"Legacy (16-bit channel)",count,len);
 if(count>32||len!=1+count*width){printf("WARN malformed/unknown list layout; hex:");for(size_t i=0;i<len && i<128;i++)printf(" %02X",v[i]);puts("");return;}
 for(unsigned i=0;i<count;i++){
  const uint8_t *p=v+1+i*width;
  unsigned radio=p[0],active=rd16(p+1);
  unsigned channel=type==0x11?rd32(p+3):rd16(p+3);
  printf("  entry %u: RAT=%s(%u) activeBandEnum=%u channel=%u",i+1,rat(radio),radio,active,channel);
  /* QmiNasActiveBand enum is NOT always the same as LTE/NR band number. */
  puts(" [raw enum; do not label as B/N without mapping]");
 }
}
int main(void){
 setvbuf(stdout,NULL,_IONBF,0);
 puts("NAS Get RF Band Information read-only; service=3 node=0 port=82 msg=0x0031");
 int fd=socket(AF_QIPCRTR,SOCK_DGRAM,0);
 if(fd<0){printf("ERROR socket errno=%d %s\n",errno,strerror(errno));return 10;}
 struct timeval tv={.tv_sec=3};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv));setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));
 struct sockaddr_qrtr dst={.sq_family=AF_QIPCRTR,.sq_node=0,.sq_port=82};
 const uint8_t req[]={0,1,0,0x31,0,0,0};
 if(sendto(fd,req,sizeof(req),0,(struct sockaddr*)&dst,sizeof(dst))!=(ssize_t)sizeof(req)){
  printf("ERROR sendto errno=%d %s\n",errno,strerror(errno));close(fd);return 12;}
 struct sockaddr_qrtr local={0},peer={0};socklen_t sl=sizeof(local);
 if(getsockname(fd,(struct sockaddr*)&local,&sl)==0)printf("Local QRTR node=%u port=%u\n",local.sq_node,local.sq_port);
 uint8_t buf[8192];sl=sizeof(peer);ssize_t n=recvfrom(fd,buf,sizeof(buf),0,(struct sockaddr*)&peer,&sl);close(fd);
 if(n<0){printf("ERROR recvfrom errno=%d %s\n",errno,strerror(errno));return 13;}
 printf("Received %zd bytes from node=%u port=%u\n",n,peer.sq_node,peer.sq_port);
 if(peer.sq_node!=0||peer.sq_port!=82||n<7||buf[0]!=2||rd16(buf+1)!=1||rd16(buf+3)!=0x31){puts("ERROR unexpected reply");return 15;}
 size_t end=7u+rd16(buf+5);if(end>(size_t)n){puts("ERROR truncated reply");return 16;}
 int seen=0,result=0,count=0;
 for(size_t pos=7;pos+3<=end;){unsigned type=buf[pos],len=rd16(buf+pos+1);pos+=3;
  if(len>end-pos){puts("ERROR malformed TLV");return 17;}
  const uint8_t*v=buf+pos;
  if(type==2&&len>=4){result=rd16(v);seen=1;printf("QMI result=%d error=%u\n",result,rd16(v+2));}
  else if(type==0x01||type==0x11){entries(v,len,type);count++;}
  else if(type==0x12){printf("Bandwidth TLV 0x12 (%u bytes); raw data:",len);for(unsigned i=0;i<len&&i<96;i++)printf(" %02X",v[i]);puts("");}
  else printf("Other TLV 0x%02X (%u bytes)\n",type,len);
  pos+=len;
 }
 if(!seen){puts("ERROR no result TLV");return 18;}
 if(result){puts("QUERY_FAILED modem returned error");return 19;}
 if(!count){puts("QUERY_OK_NO_RF_BANDS: no recognized RF band TLVs");return 0;}
 puts("QUERY_OK: active RF band/channel observations only; no full enabled-band or carrier-allowed list");
 return 0;
}
