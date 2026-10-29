/*
 * Chuyển thể từ Navbar của Medplum AppShell (Apache-2.0, xem web/NOTICE).
 * Giữ cách chọn link đang hoạt động theo độ khớp đường dẫn; bỏ bookmark và ô chọn resource type.
 */
import { AppShell as MantineAppShell, ScrollArea, Text } from '@mantine/core';
import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import classes from './AppShell.module.css';
import { activeHref, type MenuGroup } from './menus';

export interface NavbarProps {
  readonly pathname: string;
  readonly menus: readonly MenuGroup[];
  readonly closeNavbar: () => void;
}

export function Navbar(props: NavbarProps) {
  const { t } = useTranslation();
  const active = activeHref(props.pathname, props.menus);

  return (
    <MantineAppShell.Navbar p="xs" aria-label="menu">
      <ScrollArea>
        {props.menus.map((menu) => (
          <Fragment key={menu.title}>
            <Text className={classes.menuTitle}>{t(`nav.${menu.title}`)}</Text>
            {menu.links.map((link) => (
              <Link
                key={link.href}
                to={link.href}
                onClick={props.closeNavbar}
                className={`${classes.link} ${link.href === active ? classes.linkActive : ''}`}
                aria-current={link.href === active ? 'page' : undefined}
              >
                {link.icon}
                <span>{t(`nav.${link.label}`)}</span>
              </Link>
            ))}
          </Fragment>
        ))}
      </ScrollArea>
    </MantineAppShell.Navbar>
  );
}
