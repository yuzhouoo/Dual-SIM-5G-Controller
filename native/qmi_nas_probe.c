/* Qualcomm NAS Get System Selection Preference (0x0034). Read-only.
 * No setters, NV or DIAG requests. QRTR node=0 port=82 from device discovery.
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
static unsigned long long rd64(const uint8_t*p){unsigned long long v=0;for(int i=0;i<8;i++)v|=(unsigned long long)p[i]<<(8*i);return v;}
static void bandbits(const uint8_t*p,size_t n,const char*prefix,unsigned maxBands){
 if(n%8!=0||n==0){printf("  unexpected mask bytes=%zu\n",n);return;}
 unsigned count=0;printf("  ");
 for(unsigned b=1;b<=maxBands && b<=8*n;b++) if(rd64(p+((b-1)/64)*8) & (1ULL<<((b-1)%64))){printf("%s%u ",prefix,b);count++;}
 printf("(%u listed)\n",count);
 if(count==0) puts("  INFO: all-zero mask; not evidence that radio supports no bands");
}
int main(void){
 setvbuf(stdout,NULL,_IONBF,0);
 puts("NAS QRTR read-only; service=3 node=0 port=82 msg=0x0034");
 int fd=socket(AF_QIPCRTR,SOCK_DGRAM,0);
 if(fd<0){printf("ERROR socket errno=%d %s\n",errno,strerror(errno));return 10;}
 struct timeval tv={.tv_sec=3};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv));
 setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));
 struct sockaddr_qrtr dst={.sq_family=AF_QIPCRTR,.sq_node=0,.sq_port=82};
 const uint8_t request[]={0,1,0,0x34,0,0,0};
 if(sendto(fd,request,sizeof(request),0,(struct sockaddr*)&dst,sizeof(dst))!=(ssize_t)sizeof(request)){
 printf("ERROR sendto errno=%d %s\n",errno,strerror(errno));close(fd);return 12;}
 struct sockaddr_qrtr local={0},peer={0};socklen_t sl=sizeof(local);
 if(getsockname(fd,(struct sockaddr*)&local,&sl)==0)printf("Local QRTR node=%u port=%u\n",local.sq_node,local.sq_port);
 uint8_t buf[8192];sl=sizeof(peer);ssize_t nr=recvfrom(fd,buf,sizeof(buf),0,(struct sockaddr*)&peer,&sl);close(fd);
 if(nr<0){printf("ERROR recvfrom errno=%d %s\n",errno,strerror(errno));return 13;}
 printf("Received %zd bytes from node=%u port=%u\n",nr,peer.sq_node,peer.sq_port);
 if(peer.sq_node!=0||peer.sq_port!=82||nr<7||buf[0]!=2||rd16(buf+1)!=1||rd16(buf+3)!=0x34){puts("ERROR unexpected reply");return 15;}
 size_t end=7u+rd16(buf+5);if(end>(size_t)nr){puts("ERROR truncated reply");return 16;}
 int result=0,seen=0,found=0;
 for(size_t pos=7;pos+3<=end;){unsigned type=buf[pos],len=rd16(buf+pos+1);pos+=3;
 if(len>end-pos){puts("ERROR malformed TLV");return 17;}
 const uint8_t*v=buf+pos;
 if(type==2 && len>=4){result=rd16(v);seen=1;printf("QMI result=%d error=%u\n",result,rd16(v+2));}
 else if(type==0x11&&len==2)printf("Mode preference mask=0x%04X (NR flag=%u, LTE flag=%u)\n",rd16(v),(rd16(v)&0x40)!=0,(rd16(v)&0x10)!=0);
 else if(type==0x22&&len==2)printf("Disabled modes mask=0x%04X\n",rd16(v));
 else if(type==0x15&&len==8){puts("LTE 64-bit preference:");bandbits(v,len,"B",64);found++;}
 else if(type==0x23&&len==32){puts("LTE extended preference:");bandbits(v,len,"B",256);found++;}
 else if(type==0x2C&&len==64){puts("NR5G SA preference:");bandbits(v,len,"N",512);found++;}
 else if(type==0x2D&&len==64){puts("NR5G NSA preference:");bandbits(v,len,"N",512);found++;}
 else printf("Other TLV 0x%02X (%u bytes)\n",type,len);
 pos+=len;
 }
 if(!seen){puts("ERROR no result TLV");return 18;}
 if(result){puts("QUERY_FAILED modem NAS returned error");return 19;}
 if(!found){puts("QUERY_OK_NO_PREFERENCE_BANDS: no recognized band masks returned");return 20;}
 puts("QUERY_OK: NAS configured band preferences; NOT carrier-authorized bands or a guaranteed enabled-band inventory");
 return 0;
}
