/*
 * Copyright (c) 1995 Danny Gasparovski.
 *
 * Please read the file COPYRIGHT for the
 * terms and conditions of the copyright.
 */

/*
 * Set/reset terminal attributes.
 *
 * If the fd is not a tty, then do nothing.  This allows people
 * to use slirp over rsh or whatever.  A tty/pty is not necessary.
 */

#include <slirp.h>

#if __APPLE__ || defined(DO_CFSETSPEED)
speed_t
baudr(baud)
	int baud;
{
	switch (baud) {
		case 50: return B50;
		case 75: return B75;
		case 110: return B110;
		case 134: return B134;
		case 150: return B150;
		case 200: return B200;
		case 300: return B300;
		case 600: return B600;
		case 1200: return B1200;
		case 2400: return B2400;
		case 4800: return B4800;
		case 9600: return B9600;
		case 19200: return B19200;
		case 38400: return B38400;
#ifdef B57600
		case 57600: return B57600;
#endif
#ifdef B115200
		case 115200: return B115200;
#endif
		default: return B0;
	}
	return B0; /* not reached */
}

void
term_speed(ttyp)
	struct ttys *ttyp;
{
	struct termios tempio;
	if(!isatty(ttyp->fd) || ttyp->fd < 3) return;

	tcgetattr(ttyp->fd, &tempio);
	cfsetospeed(&tempio, baudr(ttyp->baud));
	cfsetispeed(&tempio, baudr(ttyp->baud));
	tcsetattr(ttyp->fd, TCSANOW, &tempio);
	tcflush(ttyp->fd, TCIOFLUSH);
}
#else
void
term_speed(ttyp)
	struct ttys *ttyp;
{
	/* no-op */
}
#endif

void
term_raw(ttyp)
	struct ttys *ttyp;
{
	struct termios tempio;

	if (!isatty(ttyp->fd))
	   return;

	tcgetattr(ttyp->fd, &tempio);
	ttyp->oldterm = tempio;
/*
    fprintf(stderr, "Setting terminal %d to %d %d %d %d\n", ttyp->fd,
        ttyp->oldterm.c_iflag,
        ttyp->oldterm.c_oflag,
        ttyp->oldterm.c_cflag,
        ttyp->oldterm.c_lflag);
*/
	tempio.c_iflag = 0;
	tempio.c_oflag = 0;
	tempio.c_lflag = 0;

	/*
	 * Ok, I'm making this the default now *sigh*
	 */
#ifndef USE_LOWCPU
	tempio.c_cc[VMIN] = 1;
	tempio.c_cc[VTIME] = 0;
#else
	tempio.c_cc[VMIN] = 255;
	tempio.c_cc[VTIME] = 2;
#endif

	tcsetattr(ttyp->fd, TCSANOW, &tempio);
	/* Cameron */
	term_speed(ttyp);
}


void
term_restore(ttyp)
	struct ttys *ttyp;
{

	if (!isatty(ttyp->fd))
	    return;

	tcsetattr(ttyp->fd, TCSANOW, &ttyp->oldterm);
/*
    fprintf(stderr, "Restoring terminal %d to %d %d %d %d\n", ttyp->fd,
        ttyp->oldterm.c_iflag,
        ttyp->oldterm.c_oflag,
        ttyp->oldterm.c_cflag,
        ttyp->oldterm.c_lflag);
*/


}

